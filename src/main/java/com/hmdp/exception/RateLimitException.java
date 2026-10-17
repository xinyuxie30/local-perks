package com.hmdp.exception;

/**
 * 触发限流。由全局异常处理转成 429，并带上 Retry-After。
 */
public class RateLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public RateLimitException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
