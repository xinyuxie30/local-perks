package com.hmdp.task;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 交易对账：以 tb_voucher_order 为账本，收敛 Redis 与 MySQL 的库存漂移，并兜底补关单。
 * 主链路只保证「尽量不错」，错过的部分由这里收口
 */
@Slf4j
@Component
public class TradeReconcileTask {

    /**
     * 未支付订单的持有时间，需与 trade.close-delay-level 表示的时间口径一致
     */
    @Value("${trade.close-delay-minutes:30}")
    private int closeDelayMinutes;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Scheduled(cron = "${trade.reconcile-cron:0 2/5 * * * ?}")
    public void reconcile() {
        closeExpiredOrders();
        checkStock();
    }

    /**
     * 兜底补关单：延迟消息丢失或消费者未起来时，未支付订单不能永远占着库存
     */
    private void closeExpiredOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(closeDelayMinutes);
        List<VoucherOrder> expired = voucherOrderService.query()
                .eq("status", OrderStatus.UNPAID)
                .lt("create_time", deadline)
                .list();
        if (expired.isEmpty()) {
            return;
        }
        log.info("对账发现 {} 笔超时未支付订单待关闭", expired.size());
        for (VoucherOrder order : expired) {
            voucherOrderService.closeOrderIfUnpaid(order.getId());
        }
    }

    /**
     * 库存校正，两条不变量：
     * Redis：total_stock - 占用集合成员数 + 已取消订单数 = 剩余库存
     * （取消/超时关闭会回补库存，但按「终身一单」语义不移除占用成员，所以要加回已取消数）
     * MySQL：total_stock - 仍占用库存的订单数 = 剩余库存
     * （退款不退库存，所以退款中与已退款仍计为占用）
     */
    private void checkStock() {
        for (SeckillVoucher voucher : seckillVoucherService.list()) {
            Long voucherId = voucher.getVoucherId();
            if (voucher.getTotalStock() == null || voucher.getTotalStock() <= 0) {
                continue;
            }
            String redisStock = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
            if (redisStock == null) {
                // 券没有预热到 Redis，入口 Lua 会直接拒绝，不存在占用
                continue;
            }
            long occupied = sizeOf(SECKILL_ORDER_KEY + voucherId);
            long canceled = voucherOrderService.query()
                    .eq("voucher_id", voucherId)
                    .eq("status", OrderStatus.CANCELED)
                    .count();
            long expectedRedisStock = voucher.getTotalStock() - occupied + canceled;
            if (Long.parseLong(redisStock) != expectedRedisStock) {
                log.warn("Redis 库存漂移，voucherId={}，实际={}，按订单表重算应为={}",
                        voucherId, redisStock, expectedRedisStock);
                stringRedisTemplate.opsForValue()
                        .set(SECKILL_STOCK_KEY + voucherId, String.valueOf(expectedRedisStock));
            }

            long consuming = voucherOrderService.query()
                    .eq("voucher_id", voucherId)
                    .in("status", Arrays.asList(OrderStatus.UNPAID, OrderStatus.PAID,
                            OrderStatus.USED, OrderStatus.REFUNDING, OrderStatus.REFUNDED))
                    .count();
            long expectedMysqlStock = voucher.getTotalStock() - consuming;
            if (voucher.getStock() == null || voucher.getStock() != expectedMysqlStock) {
                log.warn("MySQL 库存漂移，voucherId={}，实际={}，按订单表重算应为={}",
                        voucherId, voucher.getStock(), expectedMysqlStock);
                seckillVoucherService.update()
                        .set("stock", expectedMysqlStock)
                        .eq("voucher_id", voucherId)
                        .update();
            }

            Integer distinctUsers = voucherOrderMapper.countDistinctUser(voucherId);
            long distinct = distinctUsers == null ? 0L : distinctUsers;
            if (occupied > distinct) {
                // 无法区分「消息还在途中」和「消息真的丢了」，因此只告警不自动补单
                log.warn("存在占用标记但没有对应订单，voucherId={}，占用={}，有订单的用户={}",
                        voucherId, occupied, distinct);
            }
        }
    }

    private long sizeOf(String key) {
        Long size = stringRedisTemplate.opsForSet().size(key);
        return size == null ? 0L : size;
    }
}
