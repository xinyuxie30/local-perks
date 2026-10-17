package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.CommentDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.CommentVO;
import com.hmdp.vo.MyCommentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.COMMENT_LIKED_KEY;

@Slf4j
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments>
        implements IBlogCommentsService {

    private static final int MAX_CONTENT_LENGTH = 255;
    private static final int MAX_REPLIES_PREVIEW = 3;

    private static final DefaultRedisScript<Long> LIKE_TOGGLE_SCRIPT;

    static {
        LIKE_TOGGLE_SCRIPT = new DefaultRedisScript<>();
        LIKE_TOGGLE_SCRIPT.setLocation(new ClassPathResource("like_toggle.lua"));
        LIKE_TOGGLE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private IBlogService blogService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result saveComment(CommentDTO dto) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (dto == null || dto.getBlogId() == null || dto.getBlogId() <= 0) {
            return Result.fail("笔记id不合法");
        }
        String content = StrUtil.trim(dto.getContent());
        if (StrUtil.isBlank(content)) {
            return Result.fail("评论内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            return Result.fail("评论内容不能超过 " + MAX_CONTENT_LENGTH + " 个字");
        }
        Blog blog = blogService.getById(dto.getBlogId());
        if (blog == null) {
            return Result.fail("笔记不存在");
        }

        long parentId = dto.getParentId() == null ? 0L : dto.getParentId();
        long answerId = dto.getAnswerId() == null ? 0L : dto.getAnswerId();
        if (parentId < 0 || answerId < 0) {
            return Result.fail("回复关系不合法");
        }
        if (parentId > 0) {
            BlogComments parent = getById(parentId);
            if (parent == null || !isVisible(parent)) {
                return Result.fail("回复的评论不存在或已删除");
            }
            if (!parent.getBlogId().equals(dto.getBlogId())) {
                return Result.fail("不能跨笔记回复");
            }
            if (parent.getParentId() != null && parent.getParentId() != 0L) {
                return Result.fail("只支持两级评论，请回复一级评论");
            }
            if (answerId == 0L) {
                answerId = parentId;
            } else {
                BlogComments answer = getById(answerId);
                if (answer == null || !isVisible(answer)) {
                    return Result.fail("回复的目标评论不存在或已删除");
                }
                if (!answer.getBlogId().equals(dto.getBlogId())) {
                    return Result.fail("不能跨笔记回复");
                }
                boolean sameThread = parentId == answer.getId()
                        || (answer.getParentId() != null && answer.getParentId() == parentId);
                if (!sameThread) {
                    return Result.fail("回复关系不合法");
                }
            }
        } else if (answerId != 0L) {
            return Result.fail("一级评论不需要指定回复目标");
        }

        BlogComments comment = new BlogComments();
        comment.setUserId(user.getId());
        comment.setBlogId(dto.getBlogId());
        comment.setParentId(parentId);
        comment.setAnswerId(answerId);
        comment.setContent(content);
        comment.setLiked(0);
        comment.setStatus(0);
        save(comment);
        // 评论数按可见评论重算，避免并发下 +1/-1 漂移
        getBaseMapper().refreshBlogCommentCount(dto.getBlogId());
        return Result.ok(comment.getId());
    }

    @Override
    public Result queryComments(Long blogId, Integer current) {
        if (blogId == null || blogId <= 0) {
            return Result.fail("笔记id不合法");
        }
        if (current == null || current < 1 || current > SystemConstants.MAX_PAGE_NUMBER) {
            return Result.fail("页码不合法");
        }
        if (blogService.getById(blogId) == null) {
            return Result.fail("笔记不存在");
        }

        Page<CommentVO> page = new Page<>(current, SystemConstants.MAX_PAGE_SIZE);
        getBaseMapper().queryTopComments(page, blogId);
        List<CommentVO> records = page.getRecords();
        if (records.isEmpty()) {
            return Result.ok(records, page.getTotal());
        }

        List<Long> parentIds = records.stream().map(CommentVO::getId).collect(Collectors.toList());
        List<CommentVO> replies = getBaseMapper().queryReplies(parentIds);
        List<CommentVO> counts = getBaseMapper().countReplies(parentIds);
        Map<Long, List<CommentVO>> repliesByParent = new HashMap<>();
        for (CommentVO reply : replies) {
            repliesByParent.computeIfAbsent(reply.getParentId(), key -> new ArrayList<>()).add(reply);
        }
        Map<Long, Integer> countByParent = counts.stream()
                .collect(Collectors.toMap(CommentVO::getParentId, CommentVO::getReplyCount, (a, b) -> a));

        for (CommentVO top : records) {
            List<CommentVO> all = repliesByParent.getOrDefault(top.getId(), Collections.emptyList());
            top.setReplyCount(countByParent.getOrDefault(top.getId(), all.size()));
            top.setReplies(all.size() > MAX_REPLIES_PREVIEW
                    ? new ArrayList<>(all.subList(0, MAX_REPLIES_PREVIEW))
                    : all);
        }
        fillLiked(records);
        for (CommentVO top : records) {
            fillLiked(top.getReplies());
        }
        return Result.ok(records, page.getTotal());
    }

    @Override
    public Result queryMine(Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        int pageNo = (current == null || current < 1 || current > SystemConstants.MAX_PAGE_NUMBER) ? 1 : current;
        Page<MyCommentVO> page = new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE);
        getBaseMapper().queryByUser(page, user.getId());
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result deleteComment(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (id == null || id <= 0) {
            return Result.fail("评论id不合法");
        }
        BlogComments comment = getById(id);
        if (comment == null || !isVisible(comment)) {
            return Result.fail("评论不存在或已删除");
        }
        if (!comment.getUserId().equals(user.getId())) {
            return Result.fail("只能删除自己的评论");
        }
        comment.setStatus(2);
        updateById(comment);
        // 删除主评论时连带隐藏回复，避免留下没有可见父级的孤儿回复
        if (comment.getParentId() == null || comment.getParentId() == 0L) {
            update().set("status", 2).eq("parent_id", id).update();
        }
        getBaseMapper().refreshBlogCommentCount(comment.getBlogId());
        // 评论已隐藏，点赞集合没有保留意义
        stringRedisTemplate.delete(COMMENT_LIKED_KEY + id);
        return Result.ok();
    }

    @Override
    public Result likeComment(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (id == null || id <= 0) {
            return Result.fail("评论id不合法");
        }
        BlogComments comment = getById(id);
        if (comment == null || !isVisible(comment)) {
            return Result.fail("评论不存在或已删除");
        }
        String key = COMMENT_LIKED_KEY + id;
        Long toggled = stringRedisTemplate.execute(
                LIKE_TOGGLE_SCRIPT,
                Collections.singletonList(key),
                user.getId().toString(),
                String.valueOf(System.currentTimeMillis()));
        boolean liked = toggled != null && toggled == 1L;
        boolean updated;
        if (liked) {
            updated = update().setSql("liked = IFNULL(liked, 0) + 1").eq("id", id).update();
            if (!updated) {
                stringRedisTemplate.opsForZSet().remove(key, user.getId().toString());
                return Result.fail("评论不存在或已删除");
            }
        } else {
            updated = update().setSql("liked = GREATEST(IFNULL(liked, 0) - 1, 0)").eq("id", id).update();
            if (!updated) {
                return Result.fail("评论不存在或已删除");
            }
        }
        return Result.ok(liked);
    }

    private boolean isVisible(BlogComments comment) {
        return comment.getStatus() == null || comment.getStatus() == 0;
    }

    private void fillLiked(List<CommentVO> comments) {
        if (comments == null || comments.isEmpty()) {
            return;
        }
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            comments.forEach(c -> c.setLikedByMe(false));
            return;
        }
        String userId = user.getId().toString();
        for (CommentVO comment : comments) {
            Double score = stringRedisTemplate.opsForZSet().score(COMMENT_LIKED_KEY + comment.getId(), userId);
            comment.setLikedByMe(score != null);
        }
    }
}
