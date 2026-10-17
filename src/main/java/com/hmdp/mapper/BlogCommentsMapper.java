package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.entity.BlogComments;
import com.hmdp.vo.CommentVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface BlogCommentsMapper extends BaseMapper<BlogComments> {

    /**
     * 一级评论分页，联表补齐作者昵称与头像
     */
    IPage<CommentVO> queryTopComments(IPage<CommentVO> page, @Param("blogId") Long blogId);

    /**
     * 批量查询某页一级评论下的回复，按 parent_id、id 升序
     */
    List<CommentVO> queryReplies(@Param("parentIds") List<Long> parentIds);

    /**
     * 批量统计回复数，避免逐条 count 的 N+1
     */
    List<CommentVO> countReplies(@Param("parentIds") List<Long> parentIds);

    /**
     * 按当前可见评论重算 tb_blog.comments，删除评论后用它校正计数
     */
    int refreshBlogCommentCount(@Param("blogId") Long blogId);
}
