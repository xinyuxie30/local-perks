package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 1.判断到底是关注还是取关
        if (isFollow) {
            // 2.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 把关注用户的id，放入redis的set集合 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 3.取关，删除 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                // 把关注用户的id从Redis集合中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.判断
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2.求交集
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result counts(Long userId) {
        if (userId == null || userId <= 0) {
            return Result.fail("用户id不合法");
        }
        // 两条 count 都走 idx_user_id / idx_follow_user_id，量级小到可以直接查库
        Integer followee = query().eq("user_id", userId).count();
        Integer follower = query().eq("follow_user_id", userId).count();
        Map<String, Integer> counts = new HashMap<>(4);
        counts.put("followee", followee == null ? 0 : followee);
        counts.put("follower", follower == null ? 0 : follower);
        return Result.ok(counts);
    }

    @Override
    public Result followees(Long userId, Integer current) {
        return pageUsers(userId, current, true);
    }

    @Override
    public Result fans(Long userId, Integer current) {
        return pageUsers(userId, current, false);
    }

    /**
     * 关注/粉丝列表共用一个查询：查关系表拿 id，再一次批量取用户，最后按关系建立顺序回填。
     *
     * @param asFollowee true=我关注的人，false=关注我的人
     */
    private Result pageUsers(Long userId, Integer current, boolean asFollowee) {
        if (userId == null || userId <= 0) {
            return Result.fail("用户id不合法");
        }
        int pageNo = (current == null || current < 1 || current > SystemConstants.MAX_PAGE_NUMBER) ? 1 : current;
        String selfColumn = asFollowee ? "user_id" : "follow_user_id";
        String targetColumn = asFollowee ? "follow_user_id" : "user_id";

        Page<Follow> page = new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE);
        getBaseMapper().selectPage(page, new QueryWrapper<Follow>()
                .eq(selfColumn, userId).orderByDesc("id"));
        List<Follow> records = page.getRecords();
        if (records.isEmpty()) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }

        List<Long> ids = records.stream()
                .map(f -> asFollowee ? f.getFollowUserId() : f.getUserId())
                .collect(Collectors.toList());
        Map<Long, UserDTO> byId = userService.listByIds(ids).stream()
                .map(u -> BeanUtil.copyProperties(u, UserDTO.class))
                .collect(Collectors.toMap(UserDTO::getId, u -> u, (a, b) -> a));
        // listByIds 不保证顺序，按关系表的倒序回填，保证「最近关注的在最前」
        List<UserDTO> ordered = ids.stream().map(byId::get).filter(Objects::nonNull).collect(Collectors.toList());
        return Result.ok(ordered, page.getTotal());
    }
}
