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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
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
    private static final DefaultRedisScript<Long> WARMUP_STOCK_SCRIPT = new DefaultRedisScript<>();

    static {
        WARMUP_STOCK_SCRIPT.setLocation(new ClassPathResource("warmup_stock.lua"));
        WARMUP_STOCK_SCRIPT.setResultType(Long.class);
    }

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
        if (seckill.getStock() == null || seckill.getStock() < 0) {
            return Result.fail("数据库库存不合法，请先核对库存");
        }
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        String existing = stringRedisTemplate.opsForValue().get(stockKey);
        boolean writeStock = existing == null || force;
        if (writeStock) {
            // Redis 键丢失也不能按 DB 快照重建：途中消息尚未落库时会多放库存。
            // 历史取消订单也保留终身一单资格，恢复已有活动需要单独的维护流程。
            long orders = voucherOrderService.query()
                    .eq("voucher_id", voucherId)
                    .count();
            if (orders > 0) {
                return Result.fail("该券已有订单记录，不能通过预热重置库存和购买资格，请先暂停活动并核对账本");
            }
            // 检查占用、比较库存快照与写入必须同处 Lua，防止检查后抢券插入造成超卖。
            Long warmed = stringRedisTemplate.execute(WARMUP_STOCK_SCRIPT,
                    Arrays.asList(stockKey, SECKILL_ORDER_KEY + voucherId),
                    existing == null ? "" : existing, String.valueOf(seckill.getStock()));
            if (warmed == null || warmed != 1L) {
                return Result.fail("库存或购买资格已变化，预热已放弃，请查看最新统计后重试");
            }
        } else if (!existing.equals(String.valueOf(seckill.getStock()))) {
            log.warn("预热保留现有库存：voucherId={}，redis={}，db={}，差异请通过对账核查",
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
