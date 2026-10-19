package com.hmdp;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RUN_REDIS_SCRIPT_TESTS=true 时连接测试配置中的 Redis，仅操作随机测试键，不接 MySQL/MQ。
 * 直接执行生产 Lua，验证预热与抢券交错时不会覆盖有效扣减。
 */
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_SCRIPT_TESTS", matches = "true")
class StockWarmupScriptTest {
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> WARMUP = script("warmup_stock.lua");
    private static final DefaultRedisScript<Long> CLAIM = script("seckill_claim.lua");
    private String voucherId;
    private String stockKey;
    private String orderKey;

    @BeforeAll
    static void connect() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-test.yaml"));
        Properties properties = yaml.getObject();
        StandardEnvironment environment = new StandardEnvironment();
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                environment.resolveRequiredPlaceholders(properties.getProperty("spring.redis.host")),
                Integer.parseInt(environment.resolveRequiredPlaceholders(properties.getProperty("spring.redis.port"))));
        String password = environment.resolveRequiredPlaceholders(properties.getProperty("spring.redis.password"));
        if (!password.isEmpty()) config.setPassword(password);
        connection = new LettuceConnectionFactory(config, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3)).shutdownTimeout(Duration.ZERO).build());
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
    }

    @AfterAll
    static void disconnect() { if (connection != null) connection.destroy(); }

    @BeforeEach
    void keys() {
        voucherId = "regression-warmup-" + UUID.randomUUID();
        stockKey = "seckill:stock:" + voucherId;
        orderKey = "seckill:order:" + voucherId;
    }

    @AfterEach
    void cleanup() { if (redis != null) redis.delete(Arrays.asList(stockKey, orderKey)); }

    @Test
    void freshStockCanBeInitialized() {
        assertEquals(1L, warm("", "10"));
        assertEquals("10", redis.opsForValue().get(stockKey));
    }

    @Test
    void missingStockWithInFlightClaimMustNotBeRecreated() {
        redis.opsForSet().add(orderKey, "fixture-user");
        redis.expire(orderKey, Duration.ofMinutes(5));
        assertEquals(-1L, warm("", "10"));
        assertFalse(redis.hasKey(stockKey));
    }

    @Test
    void staleStockSnapshotMustNotOverwriteNewValue() {
        redis.opsForValue().set(stockKey, "9", Duration.ofMinutes(5));
        assertEquals(0L, warm("10", "10"));
        assertEquals("9", redis.opsForValue().get(stockKey));
    }

    @Test
    void concurrentInitializersCannotOverwriteEachOther() {
        assertEquals(1L, warm("", "10"));
        assertEquals(0L, warm("", "20"));
        assertEquals("10", redis.opsForValue().get(stockKey));
    }

    @Test
    void invalidStockCannotBeWritten() {
        assertEquals(-2L, warm("", "-1"));
        assertEquals(-2L, warm("", "1.5"));
        assertEquals(-2L, warm("", "invalid"));
        assertFalse(redis.hasKey(stockKey));
    }

    @Test
    void racingWarmupAndClaimAlwaysRetainsTheDeduction() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 20; i++) {
                redis.delete(orderKey);
                redis.opsForValue().set(stockKey, "10", Duration.ofMinutes(5));
                CountDownLatch start = new CountDownLatch(1);
                Future<Long> warmup = executor.submit(() -> { start.await(); return warm("10", "10"); });
                Future<Long> claim = executor.submit(() -> {
                    start.await();
                    return redis.execute(CLAIM, Collections.emptyList(), voucherId, "fixture-user");
                });
                start.countDown();
                assertEquals(0L, claim.get(5, TimeUnit.SECONDS));
                long result = warmup.get(5, TimeUnit.SECONDS);
                assertTrue(result == 1L || result == -1L);
                assertEquals("9", redis.opsForValue().get(stockKey));
                assertEquals(1L, redis.opsForSet().size(orderKey));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private long warm(String expected, String target) {
        Long result = redis.execute(WARMUP, Arrays.asList(stockKey, orderKey), expected, target);
        redis.expire(stockKey, Duration.ofMinutes(5));
        return result == null ? Long.MIN_VALUE : result;
    }

    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(name));
        script.setResultType(Long.class);
        return script;
    }
}
