package com.hmdp.exception;

/**
 * 无权限执行该操作，由全局异常处理转成 403。
 */
public class ForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message) {
        super(message);
    }
}
