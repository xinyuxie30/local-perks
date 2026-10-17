package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_CONTENT_LENGTH = 2048;
    private static final int MAX_IMAGES_LENGTH = 2048;

    /**
     * 点赞切换放在 Lua 里做：先判断再改集合，避免并发下同一用户被重复计数
     */
    private static final DefaultRedisScript<Long> LIKE_TOGGLE_SCRIPT;

    static {
        LIKE_TOGGLE_SCRIPT = new DefaultRedisScript<>();
        LIKE_TOGGLE_SCRIPT.setLocation(new ClassPathResource("like_toggle.lua"));
        LIKE_TOGGLE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        if (current == null || current < 1 || current > SystemConstants.MAX_PAGE_NUMBER) {
            return Result.fail("页码不合法");
        }
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        fillBlogUsers(records);
        fillBlogLiked(records);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogOfShop(Long shopId, Integer current) {
        if (shopId == null || shopId <= 0) {
            return Result.fail("店铺id不合法");
        }
        if (current == null || current < 1 || current > SystemConstants.MAX_PAGE_NUMBER) {
            return Result.fail("页码不合法");
        }
        Page<Blog> page = query()
                .eq("shop_id", shopId)
                .orderByDesc("liked")
                .orderByDesc("id")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        fillBlogUsers(records);
        fillBlogLiked(records);
        return Result.ok(records, page.getTotal());
    }

    @Override
    public Result queryBlogById(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("笔记id不合法");
        }
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("笔记id不合法");
        }
        if (getById(id) == null) {
            return Result.fail("笔记不存在！");
        }
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        Long toggled = stringRedisTemplate.execute(
                LIKE_TOGGLE_SCRIPT,
                Collections.singletonList(key),
                userId.toString(),
                String.valueOf(System.currentTimeMillis()));
        boolean liked = toggled != null && toggled == 1L;
        boolean updated;
        if (liked) {
            updated = update().setSql("liked = IFNULL(liked, 0) + 1").eq("id", id).update();
            if (!updated) {
                // 笔记在判断后被删除：把 Redis 里的点赞撤回，保持两边一致
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
                return Result.fail("笔记不存在！");
            }
        } else {
            // 取消点赞时把计数兜底到 0，避免历史脏数据把 liked 减成负数
            updated = update().setSql("liked = GREATEST(IFNULL(liked, 0) - 1, 0)").eq("id", id).update();
            if (!updated) {
                return Result.fail("笔记不存在！");
            }
        }
        return Result.ok(liked);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("笔记id不合法");
        }
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (blog == null) {
            return Result.fail("笔记内容不能为空");
        }
        String content = StrUtil.trim(blog.getContent());
        if (StrUtil.isBlank(content)) {
            return Result.fail("笔记正文不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            return Result.fail("笔记正文过长");
        }
        String title = StrUtil.trim(blog.getTitle());
        if (StrUtil.isBlank(title)) {
            title = content.length() > 30 ? content.substring(0, 30) : content;
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return Result.fail("笔记标题过长");
        }
        String images = blog.getImages() == null ? "" : blog.getImages();
        if (images.length() > MAX_IMAGES_LENGTH) {
            return Result.fail("图片数量或地址过长");
        }
        blog.setUserId(user.getId());
        blog.setTitle(title);
        blog.setContent(content);
        blog.setImages(images);
        blog.setLiked(0);
        blog.setComments(0);

        boolean success = save(blog);
        if (!success) {
            return Result.fail("新增笔记失败!");
        }
        // 推送笔记id给所有粉丝。粉丝量过大时这里应改为异步，当前规模同步即可
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            String key = FEED_KEY + follow.getUserId();
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (max == null || max < 0) {
            return Result.fail("滚动游标不合法");
        }
        if (offset == null || offset < 0) {
            return Result.fail("offset 不合法");
        }
        String key = FEED_KEY + user.getId();
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }

        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        fillBlogUsers(blogs);
        fillBlogLiked(blogs);

        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userId == null ? null : userService.getById(userId);
        if (user == null) {
            fillEmptyUser(blog);
            return;
        }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 批量填充作者信息：原来每条笔记都要 getById 查一次用户，一页 10 条就是 10 次 DB 往返。
     */
    private void fillBlogUsers(List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return;
        }
        Set<Long> userIds = blogs.stream()
                .map(Blog::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            blogs.forEach(this::fillEmptyUser);
            return;
        }
        Map<Long, User> userMap = new HashMap<>(userIds.size());
        List<User> users = userService.listByIds(userIds);
        if (users != null) {
            users.forEach(u -> userMap.put(u.getId(), u));
        }
        for (Blog blog : blogs) {
            User user = userMap.get(blog.getUserId());
            if (user == null) {
                fillEmptyUser(blog);
            } else {
                blog.setName(user.getNickName());
                blog.setIcon(user.getIcon());
            }
        }
    }

    private void fillEmptyUser(Blog blog) {
        // 用户注销后笔记仍然要能展示，不能因为查不到用户就 NPE
        blog.setName("已注销用户");
        blog.setIcon("");
    }

    /**
     * 批量查询点赞状态：一次 pipeline 取回所有 score，避免 N 次往返；异常时降级为逐个查询。
     */
    private void fillBlogLiked(List<Blog> blogs) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || blogs == null || blogs.isEmpty()) {
            return;
        }
        final String userId = user.getId().toString();
        try {
            List<Object> scores = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Blog blog : blogs) {
                    connection.zSetCommands().zScore(
                            (BLOG_LIKED_KEY + blog.getId()).getBytes(StandardCharsets.UTF_8),
                            userId.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            for (int i = 0; i < blogs.size(); i++) {
                blogs.get(i).setIsLike(i < scores.size() && scores.get(i) != null);
            }
        } catch (Exception e) {
            log.warn("批量查询点赞状态失败，降级为逐个查询", e);
            blogs.forEach(this::isBlogLiked);
        }
    }
}
