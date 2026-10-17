package com.hmdp;

import com.hmdp.controller.AdminController;
import com.hmdp.dto.UserDTO;
import com.hmdp.exception.ForbiddenException;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端权限：类级 @AdminOnly 必须生效，非白名单用户一律 403
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminGuardTest {

    @Resource
    private AdminController adminController;

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void nonAdminShouldBeRejected() {
        login(2L);
        assertThrows(ForbiddenException.class, () -> adminController.cacheStats());
    }

    @Test
    void anonymousShouldBeRejected() {
        assertThrows(ForbiddenException.class, () -> adminController.cacheStats());
    }

    @Test
    void adminShouldPass() {
        login(1011L);
        assertTrue(adminController.cacheStats().getSuccess());
    }

    private void login(long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }
}
