package com.hmdp.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 个人中心的「我的评价」列表项：一条评论 + 它所在的笔记与店铺。
 * <p>
 * 评价本身没有独立表，就是用户写的评论；这里把上下文一并带出来，
 * 用户不用点进去也知道这条评论是评哪家店的。
 */
@Data
public class MyCommentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long blogId;

    private Long parentId;

    private String content;

    private Integer liked;

    private LocalDateTime createTime;

    private String blogTitle;

    /**
     * 笔记封面（取 images 字段，逗号分隔，前端取第一张）
     */
    private String blogImage;

    private Long shopId;

    private String shopName;
}
