package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.CommentDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;

public interface IBlogCommentsService extends IService<BlogComments> {

    /**
     * 发布一级评论或回复
     */
    Result saveComment(CommentDTO dto);

    /**
     * 一级评论分页，附带前 3 条回复与回复总数
     */
    Result queryComments(Long blogId, Integer current);

    /**
     * 逻辑删除自己的评论
     */
    Result deleteComment(Long id);

    /**
     * 评论点赞/取消点赞
     */
    Result likeComment(Long id);
}
