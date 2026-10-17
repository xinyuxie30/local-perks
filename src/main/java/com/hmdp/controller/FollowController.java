package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id){
        return followService.followCommons(id);
    }

    /**
     * 关注数 / 粉丝数，个人中心标签页用
     */
    @GetMapping("/counts/{id}")
    public Result counts(@PathVariable("id") Long userId) {
        return followService.counts(userId);
    }

    /**
     * 我关注的人
     */
    @GetMapping("/followees/{id}")
    public Result followees(@PathVariable("id") Long userId,
                            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return followService.followees(userId, current);
    }

    /**
     * 关注我的人（粉丝）
     */
    @GetMapping("/fans/{id}")
    public Result fans(@PathVariable("id") Long userId,
                       @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return followService.fans(userId, current);
    }
}
