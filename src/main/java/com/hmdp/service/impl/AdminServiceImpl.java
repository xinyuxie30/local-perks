package com.hmdp.service.impl;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.hmdp.cache.ShopLocalCache;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IAdminService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.task.TradeReconcileTask;
import com.hmdp.utils.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SECKILL_VOUCHER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Service
public class AdminServiceImpl implements IAdminService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private IVoucherService voucherService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private TradeReconcileTask tradeReconcileTask;

    @Resource
    private ShopLocalCache shopLocalCache;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result warmUpSeckill(Long voucherId, boolean force) {
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券id不合法");
        }
        Voucher voucher = voucherService.getById(voucherId);
        SeckillVoucher seckill = seckillVoucherService.getById(voucherId);
        if (voucher == null || seckill == null) {
            return Result.fail("秒杀券不存在");
        }
        if (seckill.getTotalStock() == null || seckill.getTotalStock() <= 0) {
            seckill.setTotalStock(seckill.getStock());
            seckillVoucherService.updateById(seckill);
        }
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        String existing = stringRedisTemplate.opsForValue().get(stockKey);
        boolean writeStock = existing == null || force;
        if (writeStock && force && existing != null) {
            // force 覆盖是危险操作（审计 2026-10-18 P1-3）：异步订单尚未落库时 Redis/DB 本就允许短暂不一致，
            // 直接用 DB 库存覆盖会吞掉正常扣减。只允许在「无进行中占用」时覆盖——
            // Redis 无占用标记且订单表无未完成订单，说明活动已暂停/排空，此时覆盖才安全
            Long occupied = stringRedisTemplate.opsForSet().size(SECKILL_ORDER_KEY + voucherId);
            long consuming = voucherOrderService.query()
                    .eq("voucher_id", voucherId)
                    .in("status", java.util.Arrays.asList(OrderStatus.UNPAID, OrderStatus.PAID,
                            OrderStatus.USED, OrderStatus.REFUNDING, OrderStatus.REFUNDED))
                    .count();
            if ((occupied != null && occupied > 0) || consuming > 0) {
                log.warn("拒绝 force 覆盖 Redis 库存：存在进行中的订单占用，voucherId={}，redis占用={}，db占用={}",
                        voucherId, occupied == null ? 0L : occupied, consuming);
                return Result.fail("该券存在进行中的订单占用（Redis 占用 " + (occupied == null ? 0L : occupied)
                        + "，订单表占用 " + consuming + "），拒绝覆盖 Redis 库存。"
                        + "请先暂停活动并等待订单收敛（支付/核销/取消完成），再用 stats 查看差异。");
            }
        }
        if (writeStock) {
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(seckill.getStock()));
        } else if (!existing.equals(String.valueOf(seckill.getStock()))) {
            log.warn("预热跳过：Redis 库存与 DB 不一致，voucherId={}，redis={}，db={}，可加 force=true 覆盖",
                    voucherId, existing, seckill.getStock());
        }
        try {
            Map<String, String> window = new LinkedHashMap<>(2);
            window.put("beginTime", seckill.getBeginTime() == null ? "" : seckill.getBeginTime().format(TIME_FORMATTER));
            window.put("endTime", seckill.getEndTime() == null ? "" : seckill.getEndTime().format(TIME_FORMATTER));
            stringRedisTemplate.opsForHash().putAll(CACHE_SECKILL_VOUCHER_KEY + voucherId, window);
            stringRedisTemplate.expire(CACHE_SECKILL_VOUCHER_KEY + voucherId, 5, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("写入秒杀时间窗缓存失败，voucherId={}", voucherId, e);
        }
        return Result.ok(seckillStats(voucherId).getData());
    }

    @Override
    public Result seckillStats(Long voucherId) {
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券id不合法");
        }
        SeckillVoucher seckill = seckillVoucherService.getById(voucherId);
        if (seckill == null) {
            return Result.fail("秒杀券不存在");
        }
        String redisStock = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
        Long occupied = stringRedisTemplate.opsForSet().size(SECKILL_ORDER_KEY + voucherId);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("voucherId", voucherId);
        stats.put("dbStock", seckill.getStock());
        stats.put("totalStock", seckill.getTotalStock());
        stats.put("redisStock", redisStock);
        stats.put("redisWarmed", redisStock != null);
        stats.put("occupiedUsers", occupied == null ? 0L : occupied);
        stats.put("beginTime", seckill.getBeginTime() == null ? null : seckill.getBeginTime().format(TIME_FORMATTER));
        stats.put("endTime", seckill.getEndTime() == null ? null : seckill.getEndTime().format(TIME_FORMATTER));
        stats.put("expired", seckill.getEndTime() != null && seckill.getEndTime().isBefore(LocalDateTime.now()));
        stats.put("orderCount", countByStatus(voucherId, null));
        stats.put("unpaid", countByStatus(voucherId, OrderStatus.UNPAID));
        stats.put("paid", countByStatus(voucherId, OrderStatus.PAID));
        stats.put("used", countByStatus(voucherId, OrderStatus.USED));
        stats.put("canceled", countByStatus(voucherId, OrderStatus.CANCELED));
        stats.put("refunded", countByStatus(voucherId, OrderStatus.REFUNDED));
        return Result.ok(stats);
    }

    @Override
    public Result reconcile() {
        try {
            tradeReconcileTask.reconcile();
            return Result.ok();
        } catch (Exception e) {
            log.error("手动对账失败", e);
            return Result.fail("对账执行失败：" + e.getMessage());
        }
    }

    @Override
    public Result cacheStats() {
        CacheStats stats = shopLocalCache.stats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("estimatedSize", shopLocalCache.estimatedSize());
        result.put("hitCount", stats.hitCount());
        result.put("missCount", stats.missCount());
        result.put("hitRate", stats.hitRate());
        result.put("evictionCount", stats.evictionCount());
        result.put("loadCount", stats.loadCount());
        return Result.ok(result);
    }

    private long countByStatus(Long voucherId, Integer status) {
        return voucherOrderService.query()
                .eq("voucher_id", voucherId)
                .eq(status != null, "status", status)
                .count();
    }
}
