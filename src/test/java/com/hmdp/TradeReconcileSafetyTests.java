package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IAdminService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.task.TradeReconcileTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计（progress-review-2026-10-18）P1-3 回归：库存对账的观测/修复分离与 force 预热护栏。
 *
 * <p>需要 MySQL 与 Redis 在运行（不依赖 MQ）。验收点：</p>
 * <ul>
 *   <li>观测模式（trade.reconcile.auto-fix=false，默认）：漂移只告警，Redis/MySQL 均不被覆盖；</li>
 *   <li>修复模式：无并发时按订单表重算值修复；CAS 脚本在旧值不匹配（期间有正常交易）时必须拒绝写入；</li>
 *   <li>force 预热：存在进行中的订单占用时拒绝覆盖 Redis 库存，排空后允许恢复。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class TradeReconcileSafetyTests {

    /** 与 TradeReconcileTask 内部 CAS 脚本同款语义，用于直接验证「旧值不匹配绝不写入」 */
    private static final DefaultRedisScript<Long> CAS_STOCK_SCRIPT = new DefaultRedisScript<>(
            "local cur = redis.call('get', KEYS[1]) " +
            "if cur == ARGV[1] then " +
            "  redis.call('set', KEYS[1], ARGV[2]) " +
            "  return 1 " +
            "end " +
            "return 0", Long.class);

    @Resource
    private TradeReconcileTask tradeReconcileTask;

    @Resource
    private IAdminService adminService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private Long voucherId;

    @AfterEach
    void cleanUp() {
        ReflectionTestUtils.setField(tradeReconcileTask, "autoFix", false);
        ReflectionTestUtils.setField(tradeReconcileTask, "closeDelayMinutes", 30);
        if (voucherId == null) {
            return;
        }
        voucherOrderService.query().eq("voucher_id", voucherId).list()
                .forEach(order -> voucherOrderService.removeById(order.getId()));
        seckillVoucherService.removeById(voucherId);
        voucherService.removeById(voucherId);
        stringRedisTemplate.delete(SECKILL_STOCK_KEY + voucherId);
        stringRedisTemplate.delete(SECKILL_ORDER_KEY + voucherId);
        voucherId = null;
    }

    /** 触发对账，但把补关单的时间窗调到极大，避免测试顺手关闭演示数据里的超时订单 */
    private void runReconcileWithoutClosingOrders() {
        ReflectionTestUtils.setField(tradeReconcileTask, "closeDelayMinutes", 1_000_000);
        tradeReconcileTask.reconcile();
    }

    @Test
    void observationModeMustNotOverwriteRedisDrift() {
        voucherId = createSeckillVoucher(10);
        // 制造漂移：Redis 库存 5，但无占用、无订单（重算值应为 10）
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, "5");
        runReconcileWithoutClosingOrders();
        assertEquals("5", stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId),
                "观测模式只允许告警，不得覆盖 Redis 库存");
    }

    @Test
    void observationModeMustNotOverwriteMysqlDrift() {
        voucherId = createSeckillVoucher(10);
        // DB 库存改成 3（无订单，重算值应为 10）；不写 Redis 键以跳过 Redis 分支
        seckillVoucherService.update().set("stock", 3).eq("voucher_id", voucherId).update();
        runReconcileWithoutClosingOrders();
        SeckillVoucher after = seckillVoucherService.getById(voucherId);
        assertEquals(3, after.getStock(), "观测模式只允许告警，不得覆盖 MySQL 库存");
    }

    @Test
    void autoFixModeRepairsWhenStable() {
        voucherId = createSeckillVoucher(10);
        Map<Long, String> redisSnapshot = snapshotRedisStocks();
        Map<Long, Integer> mysqlSnapshot = snapshotMysqlStocks();
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, "5");
        try {
            ReflectionTestUtils.setField(tradeReconcileTask, "autoFix", true);
            runReconcileWithoutClosingOrders();
            assertEquals("10", stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId),
                    "修复模式在无并发时应把 Redis 库存收敛到按订单表重算值");
            SeckillVoucher after = seckillVoucherService.getById(voucherId);
            assertEquals(10, after.getStock());
        } finally {
            ReflectionTestUtils.setField(tradeReconcileTask, "autoFix", false);
            restoreOtherVouchers(redisSnapshot, mysqlSnapshot);
        }
    }

    @Test
    void casScriptMustRejectStaleOldValue() {
        voucherId = createSeckillVoucher(10);
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, "5");
        // 旧值不匹配：模拟「读到旧值之后库存又被正常交易扣减」的交错
        Long rejected = stringRedisTemplate.execute(CAS_STOCK_SCRIPT,
                Collections.singletonList(SECKILL_STOCK_KEY + voucherId), "7", "10");
        assertEquals(0L, rejected, "旧值不匹配时 CAS 必须拒绝写入");
        assertEquals("5", stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId),
                "CAS 拒绝后库存不得被覆盖");
        // 旧值匹配：安全修复
        Long applied = stringRedisTemplate.execute(CAS_STOCK_SCRIPT,
                Collections.singletonList(SECKILL_STOCK_KEY + voucherId), "5", "10");
        assertEquals(1L, applied, "旧值匹配时 CAS 应完成修复");
        assertEquals("10", stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId));
    }

    @Test
    void forceWarmUpRejectedWhileOrdersInFlight() {
        voucherId = createSeckillVoucher(10);
        // Redis 库存与 DB 不一致，且有一个进行中的占用用户
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, "5");
        stringRedisTemplate.opsForSet().add(SECKILL_ORDER_KEY + voucherId, "90001");
        try {
            Result rejected = adminService.warmUpSeckill(voucherId, true);
            assertFalse(Boolean.TRUE.equals(rejected.getSuccess()),
                    "存在进行中占用时 force 预热必须被拒绝");
            assertEquals("5", stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId),
                    "被拒绝的 force 预热不得覆盖 Redis 库存");
            // 排空后（占用清除）force 允许恢复
            stringRedisTemplate.opsForSet().remove(SECKILL_ORDER_KEY + voucherId, "90001");
            Result allowed = adminService.warmUpSeckill(voucherId, true);
            assertTrue(Boolean.TRUE.equals(allowed.getSuccess()), "排空后的 force 预热应放行");
            SeckillVoucher seckill = seckillVoucherService.getById(voucherId);
            assertEquals(String.valueOf(seckill.getStock()),
                    stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId),
                    "排空后 force 覆盖应以 DB 库存为准");
        } finally {
            stringRedisTemplate.delete(SECKILL_ORDER_KEY + voucherId);
        }
    }

    // ---- 快照/还原：修复模式会处理全表，避免顺手改动演示数据 ----

    private Map<Long, String> snapshotRedisStocks() {
        Map<Long, String> snapshot = new HashMap<>();
        for (SeckillVoucher voucher : seckillVoucherService.list()) {
            snapshot.put(voucher.getVoucherId(),
                    stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucher.getVoucherId()));
        }
        return snapshot;
    }

    private Map<Long, Integer> snapshotMysqlStocks() {
        Map<Long, Integer> snapshot = new HashMap<>();
        for (SeckillVoucher voucher : seckillVoucherService.list()) {
            snapshot.put(voucher.getVoucherId(), voucher.getStock());
        }
        return snapshot;
    }

    private void restoreOtherVouchers(Map<Long, String> redisSnapshot, Map<Long, Integer> mysqlSnapshot) {
        if (voucherId == null) {
            return;
        }
        for (Map.Entry<Long, String> entry : redisSnapshot.entrySet()) {
            if (entry.getKey().equals(voucherId)) {
                continue;
            }
            String current = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + entry.getKey());
            if (entry.getValue() == null) {
                if (current != null) {
                    stringRedisTemplate.delete(SECKILL_STOCK_KEY + entry.getKey());
                }
            } else if (!entry.getValue().equals(current)) {
                stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<Long, Integer> entry : mysqlSnapshot.entrySet()) {
            if (entry.getKey().equals(voucherId)) {
                continue;
            }
            SeckillVoucher voucher = seckillVoucherService.getById(entry.getKey());
            if (voucher != null && entry.getValue() != null
                    && !entry.getValue().equals(voucher.getStock())) {
                seckillVoucherService.update()
                        .set("stock", entry.getValue())
                        .eq("voucher_id", entry.getKey())
                        .update();
            }
        }
    }

    private Long createSeckillVoucher(int stock) {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("对账测试" + stock + "元券");
        voucher.setSubTitle("仅用于自动化测试");
        voucher.setRules("测试数据");
        voucher.setPayValue(100L);
        voucher.setActualValue(200L);
        voucher.setType(1);
        voucher.setStatus(1);
        voucher.setStock(stock);
        voucher.setBeginTime(LocalDateTime.now().minusMinutes(1));
        voucher.setEndTime(LocalDateTime.now().plusHours(1));
        voucherService.addSeckillVoucher(voucher);
        return voucher.getId();
    }
}
