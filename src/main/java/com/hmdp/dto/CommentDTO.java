package com.hmdp.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 发布评论/回复的入参
 */
@Data
public class CommentDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long blogId;

    private String content;

    /**
     * 一级评论为 0 或空；回复时传所属的一级评论 id
     */
    private Long parentId;

    /**
     * 回复的目标评论 id。回复一级评论时可省略，默认等于 parentId
     */
    private Long answerId;
}
