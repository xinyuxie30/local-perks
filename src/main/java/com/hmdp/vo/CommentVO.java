package com.hmdp.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论列表视图：一级评论带前 3 条回复和回复总数
 */
@Data
public class CommentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long userId;

    private Long blogId;

    private Long parentId;

    private Long answerId;

    private String content;

    private Integer liked;

    /**
     * 当前登录用户是否点过赞
     */
    private Boolean likedByMe;

    /**
     * 一级评论的回复总数
     */
    private Integer replyCount;

    private String nickName;

    private String icon;

    /**
     * 被回复人的昵称，回复列表里展示"回复 @xxx"
     */
    private String answerNickName;

    private LocalDateTime createTime;

    /**
     * 一级评论下最多返回 3 条回复，完整回复可分页拉取
     */
    private List<CommentVO> replies;
}
