package com.hmdp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流。基于 Redis ZSET 的滑动窗口，多实例共享同一份计数。
 * 维度为 USER 时若当前未登录，自动降级为 IP，避免匿名请求绕过限流。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流键前缀，同一接口应保持唯一，例如 seckill
     */
    String prefix();

    /**
     * 窗口内允许的最大请求数
     */
    int limit() default 10;

    /**
     * 窗口长度，单位秒
     */
    int windowSeconds() default 60;

    /**
     * 限流维度
     */
    RateLimitDimension dimension() default RateLimitDimension.IP;

    /**
     * 自定义 SpEL 表达式，例如 "#phone"。
     * 配置后优先生效，结果作为维度值；为空串时使用 dimension。
     */
    String key() default "";

    /**
     * 触发限流时的提示文案
     */
    String message() default "请求过于频繁，请稍后再试";
}
