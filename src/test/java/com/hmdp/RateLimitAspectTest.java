package com.hmdp;

import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitDimension;
import com.hmdp.exception.RateLimitException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 限流切面：窗口内放行 limit 次，第 limit+1 次拒绝，窗口滑过后恢复
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RateLimitAspectTest.ProbeConfig.class)
class RateLimitAspectTest {

    private static final String KEY = "rate:limit:test:rl:global";

    @Resource
    private Probe probe;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    @AfterEach
    void clean() {
        stringRedisTemplate.delete(KEY);
    }

    @Test
    void shouldAllowLimitThenRejectAndRecoverAfterWindow() throws Exception {
        assertDoesNotThrow(() -> probe.hit());
        assertDoesNotThrow(() -> probe.hit());
        assertDoesNotThrow(() -> probe.hit());
        assertThrows(RateLimitException.class, () -> probe.hit(), "第 4 次应被限流");

        // 窗口 2 秒，等窗口滑过后应重新放行
        Thread.sleep(2_100L);
        assertDoesNotThrow(() -> probe.hit(), "窗口滑过后应恢复");
    }

    static class Probe {

        @RateLimit(prefix = "test:rl", limit = 3, windowSeconds = 2,
                dimension = RateLimitDimension.GLOBAL)
        public String hit() {
            return "ok";
        }
    }

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        public Probe probe() {
            return new Probe();
        }
    }
}
