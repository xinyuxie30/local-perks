package com.hmdp.annotation;

/**
 * 限流维度
 */
public enum RateLimitDimension {

    /**
     * 按客户端 IP
     */
    IP,

    /**
     * 按登录用户，未登录时降级为 IP
     */
    USER,

    /**
     * 全局限流，适合保护下游资源
     */
    GLOBAL
}
