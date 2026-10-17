package com.hmdp.aspect;

import cn.hutool.core.util.StrUtil;
import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitDimension;
import com.hmdp.config.RateLimitProperties;
import com.hmdp.dto.UserDTO;
import com.hmdp.exception.RateLimitException;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

import static com.hmdp.utils.RedisConstants.RATE_LIMIT_KEY;

/**
 * 注解式限流切面。切面在登录拦截器之后执行，因此 USER 维度能拿到 ThreadLocal 中的用户。
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;
    private static final String UNKNOWN = "unknown";
    private static final int MAX_KEY_PART_LENGTH = 64;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RateLimitProperties properties;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        if (!properties.isEnabled()) {
            return joinPoint.proceed();
        }
        String dimensionValue = resolveDimensionValue(joinPoint, rateLimit);
        String key = RATE_LIMIT_KEY + rateLimit.prefix() + ":" + dimensionValue;
        long windowMillis = rateLimit.windowSeconds() * 1000L;
        String member = System.currentTimeMillis() + ":" + UUID.randomUUID().toString().replace("-", "");

        try {
            Long remaining = stringRedisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(windowMillis),
                    String.valueOf(rateLimit.limit()),
                    member);
            writeHeaders(rateLimit, remaining);
            if (remaining != null && remaining < 0) {
                throw new RateLimitException(rateLimit.message(), rateLimit.windowSeconds());
            }
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            // Redis 故障不应把业务一起拖死。默认放行并告警，需要强一致限流时把 fail-open 关掉
            if (properties.isFailOpen()) {
                log.warn("限流组件不可用，按 fail-open 放行：key={}，reason={}", key, e.getMessage());
            } else {
                log.error("限流组件不可用，按 fail-closed 拒绝：key={}", key, e);
                throw new RateLimitException(rateLimit.message(), rateLimit.windowSeconds());
            }
        }
        return joinPoint.proceed();
    }

    private String resolveDimensionValue(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        if (StrUtil.isNotBlank(rateLimit.key())) {
            String evaluated = evaluateKey(joinPoint, rateLimit.key());
            if (StrUtil.isNotBlank(evaluated)) {
                return sanitize(evaluated);
            }
        }
        switch (rateLimit.dimension()) {
            case USER:
                UserDTO user = UserHolder.getUser();
                return user == null ? ipValue() : "user_" + user.getId();
            case GLOBAL:
                return "global";
            case IP:
            default:
                return ipValue();
        }
    }

    private String evaluateKey(ProceedingJoinPoint joinPoint, String expression) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = AopUtils.getMostSpecificMethod(signature.getMethod(), joinPoint.getTarget().getClass());
            EvaluationContext context = new MethodBasedEvaluationContext(
                    null, method, joinPoint.getArgs(), parameterNameDiscoverer);
            Object value = expressionParser.parseExpression(expression).getValue(context);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            log.warn("限流 SpEL 解析失败，降级为默认维度：expression={}，reason={}", expression, e.getMessage());
            return null;
        }
    }

    private String ipValue() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "ip_" + UNKNOWN;
        }
        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Real-IP");
        if (StrUtil.isBlank(ip)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StrUtil.isNotBlank(forwarded)) {
                ip = forwarded.split(",")[0].trim();
            }
        }
        if (StrUtil.isBlank(ip)) {
            ip = request.getRemoteAddr();
        }
        return "ip_" + sanitize(ip == null ? UNKNOWN : ip);
    }

    private String sanitize(String raw) {
        String value = raw == null ? UNKNOWN : raw.trim();
        if (value.isEmpty()) {
            value = UNKNOWN;
        }
        value = value.replaceAll("[^0-9a-zA-Z._-]", "_");
        return value.length() > MAX_KEY_PART_LENGTH ? value.substring(0, MAX_KEY_PART_LENGTH) : value;
    }

    private void writeHeaders(RateLimit rateLimit, Long remaining) {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return;
        }
        HttpServletResponse response = attributes.getResponse();
        if (response == null) {
            return;
        }
        try {
            response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimit.limit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(remaining == null ? 0 : remaining, 0)));
            if (remaining != null && remaining < 0) {
                response.setHeader("Retry-After", String.valueOf(rateLimit.windowSeconds()));
            }
        } catch (Exception e) {
            log.debug("写入限流响应头失败：{}", e.getMessage());
        }
    }
}
