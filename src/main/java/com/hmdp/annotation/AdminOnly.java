package com.hmdp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理端接口标记。用户 id 命中 app.admin.user-ids 才允许访问。
 * 本项目的 /shop/**、/voucher/** 对匿名开放，因此管理写操作必须额外加这层校验。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminOnly {
}
