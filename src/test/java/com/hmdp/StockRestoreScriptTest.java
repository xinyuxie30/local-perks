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
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the production Lua against Redis, using only random, per-test keys. */
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_SCRIPT_TESTS", matches = "true")
class StockRestoreScriptTest {
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> RESTORE = new DefaultRedisScript<>();
    private String stockKey;
    private String markerKey;
    private String otherMarkerKey;
    private String orderKey;

    @BeforeAll
    static void connect() {
        RESTORE.setLocation(new ClassPathResource("restore_stock.lua"));
        RESTORE.setResultType(Long.class);
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
        String voucher = "regression-restore-" + UUID.randomUUID();
        stockKey = "seckill:stock:" + voucher;
        markerKey = "seckill:stock:restored:" + voucher + ":event-1";
        otherMarkerKey = "seckill:stock:restored:" + voucher + ":event-2";
        orderKey = "seckill:order:" + voucher;
    }

    @AfterEach
    void cleanup() { if (redis != null) redis.delete(Arrays.asList(stockKey, markerKey, otherMarkerKey, orderKey)); }

    @Test
    void lostResponseAndRedeliveryDoNotRestoreTwice() {
        redis.opsForValue().set(stockKey, "3");
        assertEquals(1L, restore("10", "event-1"));
        assertEquals(2L, restore("10", "event-1"));
        assertEquals("4", redis.opsForValue().get(stockKey));
        assertEquals("event-1", redis.opsForValue().get(markerKey));
        assertEquals(-1L, redis.getExpire(markerKey, TimeUnit.MILLISECONDS));
    }

    @Test
    void runtimeFailureAfterWriteStillLeavesBothStockAndIdempotencyMarker() {
        redis.opsForValue().set(stockKey, "3");
        // Redis does not roll Lua back. Inject an error after the production script
        // to verify a retry still sees the marker accompanying the committed stock.
        DefaultRedisScript<Long> failureAfterWrite = new DefaultRedisScript<>(
                "local function restore()\n" + RESTORE.getScriptAsString()
                        + "\nend\nlocal result = restore()\n"
                        + "if result == 1 then error('injected failure after write') end\nreturn result", Long.class);
        assertThrows(org.springframework.data.redis.RedisSystemException.class,
                () -> redis.execute(failureAfterWrite, Arrays.asList(stockKey, markerKey), "10", "event-1"));
        assertEquals("4", redis.opsForValue().get(stockKey));
        assertEquals("event-1", redis.opsForValue().get(markerKey));
        assertEquals(2L, restore("10", "event-1"));
        assertEquals("4", redis.opsForValue().get(stockKey));
    }

    @Test
    void differentEventsEachRestoreOnce() {
        redis.opsForValue().set(stockKey, "3");
        assertEquals(1L, restore("10", "event-1"));
        assertEquals(1L, execute(otherMarkerKey, "10", "event-2"));
        assertEquals("5", redis.opsForValue().get(stockKey));
    }

    @Test
    void missingStockIsNotRecreatedOrMarkedProcessed() {
        assertEquals(-1L, restore("10", "event-1"));
        assertFalse(redis.hasKey(stockKey));
        assertFalse(redis.hasKey(markerKey));
    }

    @Test
    void priorSuccessRemainsDuplicateEvenIfStockLaterDisappears() {
        redis.opsForValue().set(markerKey, "event-1");
        assertEquals(2L, restore("10", "event-1"));
        assertFalse(redis.hasKey(stockKey));
    }

    @Test
    void wrongStockTypeDoesNotChangeEitherKey() {
        redis.opsForList().rightPush(stockKey, "3");
        assertEquals(-2L, restore("10", "event-1"));
        assertEquals("3", redis.opsForList().index(stockKey, 0));
        assertFalse(redis.hasKey(markerKey));
    }

    @Test
    void invalidStockValuesAreRejectedBeforeAnyWrite() {
        for (String invalid : Arrays.asList("-1", "1.5", "abc", "1e3", "2147483648", "")) {
            redis.opsForValue().set(stockKey, invalid);
            assertEquals(-2L, restore("10", "event-1"), invalid);
            assertEquals(invalid, redis.opsForValue().get(stockKey));
            assertFalse(redis.hasKey(markerKey));
        }
    }

    @Test
    void invalidArgumentsAreRejectedBeforeAnyWrite() {
        redis.opsForValue().set(stockKey, "3");
        for (String invalid : Arrays.asList("0", "-1", "1.5", "1e3", "2147483648", "")) {
            assertEquals(-4L, restore(invalid, "event-1"), invalid);
        }
        assertEquals(-4L, restore("10", ""));
        assertEquals(-4L, redis.execute(RESTORE, Collections.singletonList(stockKey), "10", "event-1"));
        assertEquals(-4L, redis.execute(RESTORE, Arrays.asList(stockKey, stockKey), "10", "event-1"));
        assertEquals("3", redis.opsForValue().get(stockKey));
        assertFalse(redis.hasKey(markerKey));
    }

    @Test
    void invalidMarkerCannotCauseStockIncrement() {
        redis.opsForValue().set(stockKey, "3");
        redis.opsForSet().add(markerKey, "unexpected");
        assertEquals(-5L, restore("10", "event-1"));
        assertTrue(redis.opsForSet().isMember(markerKey, "unexpected"));
        assertEquals("3", redis.opsForValue().get(stockKey));
        redis.delete(markerKey);
        redis.opsForValue().set(markerKey, "different-event");
        assertEquals(-5L, restore("10", "event-1"));
        assertEquals("different-event", redis.opsForValue().get(markerKey));
        assertEquals("3", redis.opsForValue().get(stockKey));
    }

    @Test
    void capacityFailureRemainsUnprocessedAndCanBeRetriedAfterRepair() {
        redis.opsForValue().set(stockKey, "10");
        assertEquals(-3L, restore("10", "event-1"));
        assertFalse(redis.hasKey(markerKey));
        assertEquals("10", redis.opsForValue().get(stockKey));
        redis.opsForValue().set(stockKey, "11");
        assertEquals(-3L, restore("10", "event-1"));
        assertEquals("11", redis.opsForValue().get(stockKey));
        redis.opsForValue().set(stockKey, "9");
        assertEquals(1L, restore("10", "event-1"));
        assertEquals("10", redis.opsForValue().get(stockKey));
        assertEquals(2L, restore("10", "event-1"));
    }

    @Test
    void stockExpiryAndLifetimePurchaseRecordArePreserved() {
        redis.opsForValue().set(stockKey, "3", Duration.ofMinutes(5));
        redis.opsForSet().add(orderKey, "fixture-user");
        long previousTtl = redis.getExpire(stockKey, TimeUnit.MILLISECONDS);
        assertEquals(1L, restore("10", "event-1"));
        long remainingTtl = redis.getExpire(stockKey, TimeUnit.MILLISECONDS);
        assertTrue(remainingTtl > 0 && remainingTtl <= previousTtl);
        assertTrue(redis.opsForSet().isMember(orderKey, "fixture-user"));
        assertEquals(-1L, redis.getExpire(markerKey, TimeUnit.MILLISECONDS));
    }

    @Test
    void integerBoundaryIsHandledWithoutRounding() {
        redis.opsForValue().set(stockKey, "2147483646");
        assertEquals(1L, restore("2147483647", "event-1"));
        assertEquals("2147483647", redis.opsForValue().get(stockKey));
    }

    @Test
    void concurrentDuplicateDeliveriesOnlyIncrementOnce() throws Exception {
        redis.opsForValue().set(stockKey, "0");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                results.add(executor.submit(() -> { start.await(); return restore("10", "event-1"); }));
            }
            start.countDown();
            int restored = 0;
            for (Future<Long> result : results) {
                long code = result.get(5, TimeUnit.SECONDS);
                assertTrue(code == 1L || code == 2L);
                if (code == 1L) restored++;
            }
            assertEquals(1, restored);
            assertEquals("1", redis.opsForValue().get(stockKey));
        } finally {
            executor.shutdownNow();
        }
    }

    private long restore(String total, String event) { return execute(markerKey, total, event); }

    private long execute(String marker, String total, String event) {
        Long result = redis.execute(RESTORE, Arrays.asList(stockKey, marker), total, event);
        return result == null ? Long.MIN_VALUE : result;
    }
}
