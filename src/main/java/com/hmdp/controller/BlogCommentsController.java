package com.hmdp.controller;

import com.hmdp.annotation.RateLimit;
import com.hmdp.annotation.RateLimitDimension;
import com.hmdp.dto.CommentDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    /**
     * 发布评论或回复。写接口按用户限流，避免刷评论
     */
    @PostMapping
    @RateLimit(prefix = "comment:save", limit = 3, windowSeconds = 5,
            dimension = RateLimitDimension.USER, message = "评论太快了，休息一下")
    public Result saveComment(@RequestBody CommentDTO dto) {
        return blogCommentsService.saveComment(dto);
    }

    /**
     * 个人中心的「我的评价」分页：评论 + 所属笔记与店铺
     */
    @GetMapping("/of/user")
    public Result queryMine(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogCommentsService.queryMine(current);
    }

    /**
     * 一级评论分页，每条附带前 3 条回复与回复总数
     */
    @GetMapping("/of/blog/{blogId}")
    public Result queryComments(@PathVariable("blogId") Long blogId,
                                @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogCommentsService.queryComments(blogId, current);
    }

    /**
     * 逻辑删除自己的评论
     */
    @DeleteMapping("/{id}")
    @RateLimit(prefix = "comment:delete", limit = 5, windowSeconds = 10,
            dimension = RateLimitDimension.USER)
    public Result deleteComment(@PathVariable("id") Long id) {
        return blogCommentsService.deleteComment(id);
    }

    /**
     * 评论点赞/取消点赞
     */
    @PostMapping("/{id}/like")
    @RateLimit(prefix = "comment:like", limit = 10, windowSeconds = 10,
            dimension = RateLimitDimension.USER)
    public Result likeComment(@PathVariable("id") Long id) {
        return blogCommentsService.likeComment(id);
    }
}
