package com.hmdp.aspect;

import com.hmdp.annotation.AdminOnly;
import com.hmdp.config.AdminProperties;
import com.hmdp.dto.UserDTO;
import com.hmdp.exception.ForbiddenException;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 管理端写接口的权限校验。用户id 未命中白名单一律拒绝，未登录同样拒绝。
 */
@Slf4j
@Aspect
@Component
public class AdminGuardAspect {

    @Resource
    private AdminProperties adminProperties;

    @Before("@annotation(adminOnly) || @within(adminOnly)")
    public void checkAdmin(AdminOnly adminOnly) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new ForbiddenException("请先登录管理员账号");
        }
        if (!adminProperties.getUserIds().contains(user.getId())) {
            log.warn("非管理员尝试访问管理接口，userId={}", user.getId());
            throw new ForbiddenException("无权限执行该操作");
        }
    }
}
