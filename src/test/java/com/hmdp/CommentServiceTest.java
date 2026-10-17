package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.CommentDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.CommentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评论模块边界：层级、跨笔记、越权删除、点赞切换、计数校正
 */
@SpringBootTest
@ActiveProfiles("test")
class CommentServiceTest {

    private static final long OWNER = 1011L;
    private static final long OTHER = 2L;

    @Resource
    private IBlogService blogService;

    @Resource
    private IBlogCommentsService commentsService;

    @Resource
    private BlogCommentsMapper commentsMapper;

    @Resource
    private BlogMapper blogMapper;

    private Long blogId;
    private Long secondBlogId;

    @BeforeEach
    void setUp() {
        login(OWNER);
        blogId = createBlog("评论测试-主贴");
        secondBlogId = createBlog("评论测试-另一贴");
    }

    @AfterEach
    void tearDown() {
        for (Long id : new Long[]{blogId, secondBlogId}) {
            if (id == null) {
                continue;
            }
            commentsMapper.delete(new QueryWrapper<BlogComments>().eq("blog_id", id));
            blogMapper.deleteById(id);
        }
        UserHolder.removeUser();
    }

    @Test
    void commentLifecycleWithBoundaryChecks() {
        // 一级评论
        Result saved = commentsService.saveComment(comment(blogId, "第一条评论", 0L, 0L));
        assertTrue(saved.getSuccess(), saved.getErrorMsg());
        Long topId = (Long) saved.getData();
        assertNotNull(topId);

        // 同一个人再评一条，用于验证分页与计数
        assertTrue(commentsService.saveComment(comment(blogId, "第二条评论", 0L, 0L)).getSuccess());

        // 回复（用户 2）
        login(OTHER);
        Result replySaved = commentsService.saveComment(comment(blogId, "回复一下", topId, 0L));
        assertTrue(replySaved.getSuccess(), replySaved.getErrorMsg());
        Long replyId = (Long) replySaved.getData();

        // 列表：两条一级评论，第一条有 1 条回复
        login(OWNER);
        Result listResult = commentsService.queryComments(blogId, 1);
        assertTrue(listResult.getSuccess());
        List<CommentVO> list = (List<CommentVO>) listResult.getData();
        assertEquals(2, list.size());
        CommentVO top = list.stream().filter(c -> c.getId().equals(topId)).findFirst().orElseThrow(AssertionError::new);
        assertEquals(1, top.getReplyCount());
        assertEquals(1, top.getReplies().size());
        assertEquals("回复一下", top.getReplies().get(0).getContent());
        // 回复的目标是一级评论，answerNickName 应为一级评论作者（1011）
        assertEquals("user_j3a6gguo1f", top.getReplies().get(0).getAnswerNickName());

        // 三级回复应被拒绝
        Result third = commentsService.saveComment(comment(blogId, "套娃", replyId, replyId));
        assertFalse(third.getSuccess());
        assertTrue(third.getErrorMsg().contains("两级"));

        // 跨笔记回复应被拒绝
        Result cross = commentsService.saveComment(comment(secondBlogId, "跨贴", topId, 0L));
        assertFalse(cross.getSuccess());
        assertTrue(cross.getErrorMsg().contains("跨笔记"));

        // 参数边界
        assertFalse(commentsService.saveComment(comment(blogId, "   ", 0L, 0L)).getSuccess());
        assertFalse(commentsService.saveComment(comment(blogId, repeat("x", 256), 0L, 0L)).getSuccess());
        assertFalse(commentsService.saveComment(comment(999_999_999L, "无贴", 0L, 0L)).getSuccess());
        assertFalse(commentsService.queryComments(blogId, 0).getSuccess());
        assertFalse(commentsService.queryComments(999_999_999L, 1).getSuccess());

        // 点赞切换
        Result like = commentsService.likeComment(topId);
        assertTrue(like.getSuccess());
        assertEquals(Boolean.TRUE, like.getData());
        assertEquals(1, commentsMapper.selectById(topId).getLiked());
        Result unlike = commentsService.likeComment(topId);
        assertEquals(Boolean.FALSE, unlike.getData());
        assertEquals(0, commentsMapper.selectById(topId).getLiked());

        // 越权删除
        login(OTHER);
        Result forbidden = commentsService.deleteComment(topId);
        assertFalse(forbidden.getSuccess());
        assertTrue(forbidden.getErrorMsg().contains("自己"));

        // 回复作者删除自己的回复，一级评论作者删除主评论
        login(OTHER);
        assertTrue(commentsService.deleteComment(replyId).getSuccess());
        login(OWNER);
        assertTrue(commentsService.deleteComment(topId).getSuccess());
        List<CommentVO> after = (List<CommentVO>) commentsService.queryComments(blogId, 1).getData();
        assertEquals(1, after.size(), "只剩第二条一级评论");
        assertEquals(1, blogMapper.selectById(blogId).getComments().intValue());
    }

    private CommentDTO comment(Long targetBlogId, String content, Long parentId, Long answerId) {
        CommentDTO dto = new CommentDTO();
        dto.setBlogId(targetBlogId);
        dto.setContent(content);
        dto.setParentId(parentId);
        dto.setAnswerId(answerId);
        return dto;
    }

    private Long createBlog(String title) {
        Blog blog = new Blog();
        blog.setShopId(1L);
        blog.setTitle(title);
        blog.setContent(title + " 正文");
        blog.setImages("");
        Result result = blogService.saveBlog(blog);
        assertTrue(result.getSuccess(), result.getErrorMsg());
        return (Long) result.getData();
    }

    private void login(long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setNickName("test-" + userId);
        UserHolder.saveUser(user);
    }

    private String repeat(String s, int times) {
        StringBuilder builder = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(s);
        }
        return builder.toString();
    }
}
