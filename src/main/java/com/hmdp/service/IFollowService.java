package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result followCommons(Long id);

    /**
     * 关注数 / 粉丝数
     */
    Result counts(Long userId);

    /**
     * 我关注的人（分页）
     */
    Result followees(Long userId, Integer current);

    /**
     * 关注我的人（粉丝，分页）
     */
    Result fans(Long userId, Integer current);
}
