-- 交易链路改造的增量脚本，在已导入 hmdp.sql 的库上执行即可，不要重跑全量脚本
USE hmdp;

-- 一人一单的第三层防线：数据库唯一索引兜底
-- 终身一单语义：订单行永不删除，取消/退款后仍保留，因此该用户无法再次购买同一张券
ALTER TABLE tb_voucher_order
    ADD UNIQUE INDEX uk_user_voucher (user_id, voucher_id);

-- 关单与对账都按（状态 + 下单时间）扫描，避免全表扫
ALTER TABLE tb_voucher_order
    ADD INDEX idx_status_create (status, create_time);

-- 对账基准：Redis 侧只有「剩余库存」，没有总库存就无法判断漂移方向
ALTER TABLE tb_seckill_voucher
    ADD COLUMN total_stock INT(8) NOT NULL DEFAULT 0 COMMENT '总库存，对账基准' AFTER stock;
UPDATE tb_seckill_voucher SET total_stock = stock;
