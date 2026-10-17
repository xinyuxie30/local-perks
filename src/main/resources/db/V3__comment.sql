-- 评论模块增量脚本，在已执行 hmdp.sql + V2__trade.sql 的库上执行一次
USE hmdp;

-- 历史数据里 liked / status 允许 NULL，先归一化再收紧约束
UPDATE tb_blog_comments SET liked = 0 WHERE liked IS NULL;
UPDATE tb_blog_comments SET status = 0 WHERE status IS NULL;

ALTER TABLE tb_blog_comments
    MODIFY COLUMN `liked` INT(8) NOT NULL DEFAULT 0 COMMENT '点赞数',
    MODIFY COLUMN `status` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '状态，0：正常，1：被举报，2：禁止查看';

-- 一级评论分页：blog_id + parent_id + 排序字段
ALTER TABLE tb_blog_comments
    ADD INDEX idx_blog_parent (blog_id, parent_id, id);

-- 回复批量拉取与计数：parent_id + id
ALTER TABLE tb_blog_comments
    ADD INDEX idx_parent (parent_id, id);

-- 删除评论后按用户维度审计
ALTER TABLE tb_blog_comments
    ADD INDEX idx_user (user_id, id);
