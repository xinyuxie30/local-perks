package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.ProfileDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result logout(String token);

    Result sign();

    Result signCount();

    /**
     * 更新个人资料（昵称 + 详情），并同步 Redis 登录态里的昵称，保证 /user/me 立即生效。
     */
    Result updateProfile(Long userId, String token, ProfileDTO dto);

}
