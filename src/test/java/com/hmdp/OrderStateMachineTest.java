package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.OrderStatus;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.*;

import static com.hmdp.utils.RedisConstants.CACHE_SECKILL_VOUCHER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.hmdp.utils.RedisConstants.SECKILL_QUEUE_KEY;

/**
 * 订单状态机与库存回补边界。直接调用 createOrder 避免依赖 MQ 的异步时序，
 * 但 closeOrderIfUnpaid / cancelOrder 的回补逻辑与线上一致。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderStateMachineTest {

    private static final long USER_A = 1011L;
    private static final long USER_B = 1012L;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Resource private com.hmdp.service.OrderBatchWriter batchWriter;
    @Resource private com.hmdp.mq.OrderPersistenceBatcher batcher;

    private final List<Long> createdVouchers = new ArrayList<>();

    @Test
    void batchStockFailureMustRollBackEveryInsertedOrderAndEarlierVoucherDeduction() {
        Long first = createVoucher(2, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        Long second = createVoucher(1, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        List<SeckillOrderMessage> messages = new ArrayList<>();
        messages.add(new SeckillOrderMessage(redisIdWorker.nextId("test"), USER_A, first));
        messages.add(new SeckillOrderMessage(redisIdWorker.nextId("test"), USER_A, second));
        messages.add(new SeckillOrderMessage(redisIdWorker.nextId("test"), USER_B, second));
        assertThrows(IllegalStateException.class, () -> batchWriter.write(messages));
        assertEquals(2, stockOf(first));
        assertEquals(1, stockOf(second));
        for (SeckillOrderMessage message : messages) assertNull(voucherOrderService.getById(message.getOrderId()));
    }

    @Test
    void batcherMustCommitValidOrdersAndIsolateDuplicateDeliveries() throws Exception {
        Long voucherId = createVoucher(16, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<SeckillOrderMessage> messages = new ArrayList<>();
        for (int i = 0; i < 16; i++) messages.add(new SeckillOrderMessage(redisIdWorker.nextId("test"), 2000000L+i, voucherId));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                SeckillOrderMessage message = messages.get(i % 16);
                futures.add(pool.submit(() -> {
                    start.await();
                    batcher.createOrder(message);
                    // A normal return must mean the SQL commit is externally visible.
                    assertNotNull(voucherOrderService.getById(message.getOrderId()));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(35, TimeUnit.SECONDS);
            assertEquals(16, voucherOrderService.query().eq("voucher_id", voucherId).count().intValue());
            assertEquals(0, stockOf(voucherId));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void successfulBatchMustCommitAllOrdersWithAggregatedStock() {
        Long voucherId = createVoucher(3, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        List<SeckillOrderMessage> messages = java.util.Arrays.asList(
                new SeckillOrderMessage(redisIdWorker.nextId("test"), USER_A, voucherId),
                new SeckillOrderMessage(redisIdWorker.nextId("test"), USER_B, voucherId));
        batchWriter.write(messages);
        assertEquals(1, stockOf(voucherId));
        for (SeckillOrderMessage message : messages) assertNotNull(voucherOrderService.getById(message.getOrderId()));
    }

    @BeforeEach
    void setUp() {
        login(USER_A);
    }

    @AfterEach
    void tearDown() {
        for (Long voucherId : createdVouchers) {
            voucherOrderService.remove(new QueryWrapper<VoucherOrder>().eq("voucher_id", voucherId));
            seckillVoucherService.removeById(voucherId);
            voucherService.removeById(voucherId);
            stringRedisTemplate.delete(SECKILL_STOCK_KEY + voucherId);
            stringRedisTemplate.delete(SECKILL_ORDER_KEY + voucherId);
            stringRedisTemplate.delete(CACHE_SECKILL_VOUCHER_KEY + voucherId);
        }
        createdVouchers.clear();
        UserHolder.removeUser();
    }

    @Test
    void concurrentDuplicateMessagesMustOnlyDeductOnce() throws Exception {
        Long voucherId = createVoucher(10, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        long id = redisIdWorker.nextId("test");
        long alternateId = redisIdWorker.nextId("test");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final long deliveryId = i % 2 == 0 ? id : alternateId;
                futures.add(pool.submit(() -> {
                    start.await();
                    // 唯一键并发插入可产生 InnoDB 死锁；生产消费者会抛出并由 MQ 重投。
                    // 用同一消息模拟这个边界，最终仍必须只有一条订单、扣一次库存。
                    for (int attempt = 0; ; attempt++) {
                        try {
                            voucherOrderService.createOrder(new SeckillOrderMessage(deliveryId, USER_A, voucherId));
                            break;
                        } catch (org.springframework.dao.DeadlockLoserDataAccessException e) {
                            if (attempt >= 4) throw e;
                            Thread.sleep(10L * (attempt + 1));
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
            assertEquals(9, stockOf(voucherId));
            assertEquals(1, voucherOrderService.query().eq("voucher_id", voucherId).count().intValue());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedStockDeductionMustRollBackInsertedOrder() {
        Long voucherId = createVoucher(1, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        seckillVoucherService.update().set("stock", 0).eq("voucher_id", voucherId).update();
        long id = redisIdWorker.nextId("test");
        assertThrows(IllegalStateException.class,
                () -> voucherOrderService.createOrder(new SeckillOrderMessage(id, USER_A, voucherId)));
        assertNull(voucherOrderService.getById(id));
        assertEquals(0, stockOf(voucherId));
    }

    @Test
    void redisStockMustOnlyReturnAfterDatabaseCommit() {
        Long voucherId = createVoucher(1, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        long id = redisIdWorker.nextId("test");
        voucherOrderService.createOrder(new SeckillOrderMessage(id, USER_A, voucherId));
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, "0");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            assertTrue(voucherOrderService.closeOrderIfUnpaid(id));
            assertEquals("0", stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(OrderStatus.UNPAID, voucherOrderService.getById(id).getStatus());
        assertEquals(0, stockOf(voucherId));
        assertEquals("0", stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId));
        tx.execute(status -> {
            assertTrue(voucherOrderService.closeOrderIfUnpaid(id));
            assertEquals("0", stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId));
            return null;
        });
        assertEquals("1", stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId));
    }

    @Test
    void acceptedPendingStateMustOutliveInitialTtlAndKeepOwnership() throws Exception {
        Long voucherId = createVoucher(1, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        long id = redisIdWorker.nextId("test");
        String key = SECKILL_QUEUE_KEY + id;
        try {
            stringRedisTemplate.opsForValue().set(key, "PENDING:" + USER_A, 1, TimeUnit.SECONDS);
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setLocation(new ClassPathResource("seckill.lua"));
            script.setResultType(Long.class);
            assertEquals(Long.valueOf(0), stringRedisTemplate.execute(script, Collections.emptyList(),
                    voucherId.toString(), String.valueOf(USER_A), String.valueOf(id)));
            Thread.sleep(1100);
            assertEquals(Long.valueOf(-1), stringRedisTemplate.getExpire(key));
            assertEquals("PENDING", ((com.hmdp.vo.OrderStatusVO) voucherOrderService.queryOrderStatus(id).getData()).getState());
            login(USER_B);
            assertFalse(voucherOrderService.queryOrderStatus(id).getSuccess());
        } finally {
            stringRedisTemplate.delete(key);
            stringRedisTemplate.delete("seckill:tx:" + id);
        }
    }

    @Test
    void stateMachineShouldEnforceCasTransitions() {
        Long voucherId = createVoucher(2, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        long orderId = redisIdWorker.nextId("test");
        SeckillOrderMessage message = new SeckillOrderMessage(orderId, USER_A, voucherId);

        voucherOrderService.createOrder(message);
        assertEquals(1, stockOf(voucherId));
        // 重复投递同一消息，幂等跳过，不能二次扣库存
        voucherOrderService.createOrder(message);
        assertEquals(1, stockOf(voucherId));
        assertEquals(1, voucherOrderService.query().eq("voucher_id", voucherId).count().intValue());

        // 非法支付方式
        assertFalse(voucherOrderService.payOrder(orderId, 9).getSuccess());
        assertTrue(voucherOrderService.payOrder(orderId, 1).getSuccess());
        assertFalse(voucherOrderService.payOrder(orderId, 3).getSuccess(), "重复支付应被 CAS 挡下");
        assertEquals(OrderStatus.PAID, voucherOrderService.getById(orderId).getStatus());

        // 已支付不能取消
        assertFalse(voucherOrderService.cancelOrder(orderId).getSuccess());
        assertTrue(voucherOrderService.useOrder(orderId).getSuccess());
        assertFalse(voucherOrderService.useOrder(orderId).getSuccess(), "重复核销应失败");
        // 已核销不能退款
        assertFalse(voucherOrderService.refundOrder(orderId).getSuccess());
        assertEquals(OrderStatus.USED, voucherOrderService.getById(orderId).getStatus());

        // 归属校验：别人看不到我的订单状态
        login(USER_B);
        assertFalse(voucherOrderService.queryOrderStatus(orderId).getSuccess());
        login(USER_A);
        Result status = voucherOrderService.queryOrderStatus(orderId);
        assertTrue(status.getSuccess());
        assertEquals("CREATED", ((com.hmdp.vo.OrderStatusVO) status.getData()).getState());
    }

    @Test
    void cancelAndCloseShouldRestoreStockWithoutCreatingMissingRedisKey() {
        Long voucherId = createVoucher(2, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        long orderA = redisIdWorker.nextId("test");
        voucherOrderService.createOrder(new SeckillOrderMessage(orderA, USER_A, voucherId));
        assertEquals(1, stockOf(voucherId));
        // 模拟入口 Lua 已把 Redis 库存扣到 0，取消后应回补到 1
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, "0");
        assertTrue(voucherOrderService.cancelOrder(orderA).getSuccess());
        assertEquals(2, stockOf(voucherId));
        assertEquals("1", stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId));

        login(USER_B);
        long orderB = redisIdWorker.nextId("test");
        voucherOrderService.createOrder(new SeckillOrderMessage(orderB, USER_B, voucherId));
        assertEquals(1, stockOf(voucherId));
        // Redis 库存 key 被清空（未预热/被淘汰）：关单只能改 DB，绝不能凭空创建 key
        stringRedisTemplate.delete(SECKILL_STOCK_KEY + voucherId);
        assertTrue(voucherOrderService.closeOrderIfUnpaid(orderB));
        assertEquals(2, stockOf(voucherId));
        assertNull(stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId));
        assertFalse(voucherOrderService.closeOrderIfUnpaid(orderB), "已关闭的订单不应重复处理");
    }

    @Test
    void seckillShouldRejectInvalidWindowAndArguments() {
        Long expired = createVoucher(1, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(1));
        Result expiredResult = voucherOrderService.seckillVoucher(expired);
        assertFalse(expiredResult.getSuccess());
        assertTrue(expiredResult.getErrorMsg().contains("已经结束"));

        Long future = createVoucher(1, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));
        Result futureResult = voucherOrderService.seckillVoucher(future);
        assertFalse(futureResult.getSuccess());
        assertTrue(futureResult.getErrorMsg().contains("尚未开始"));

        assertFalse(voucherOrderService.seckillVoucher(0L).getSuccess());
        assertFalse(voucherOrderService.seckillVoucher(-1L).getSuccess());
        assertFalse(voucherOrderService.queryMyOrders(0, null).getSuccess());
        assertFalse(voucherOrderService.queryMyOrders(1, 99).getSuccess());
        assertFalse(voucherOrderService.queryOrderDetail(0L).getSuccess());
        assertFalse(voucherOrderService.payOrder(0L, 1).getSuccess());
        assertFalse(voucherOrderService.cancelOrder(0L).getSuccess());
        assertFalse(voucherOrderService.useOrder(0L).getSuccess());
        assertFalse(voucherOrderService.refundOrder(0L).getSuccess());
    }

    @Test
    void missingStockKeyShouldFailFast() {
        Long voucherId = createVoucher(1, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        stringRedisTemplate.delete(SECKILL_STOCK_KEY + voucherId);
        Result result = voucherOrderService.seckillVoucher(voucherId);
        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("未预热"), result.getErrorMsg());
    }

    private Long createVoucher(int stock, LocalDateTime begin, LocalDateTime end) {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("状态机测试-" + System.nanoTime());
        voucher.setSubTitle("自动化测试");
        voucher.setRules("测试数据");
        voucher.setPayValue(100L);
        voucher.setActualValue(200L);
        voucher.setType(1);
        voucher.setStatus(1);
        voucher.setStock(stock);
        voucher.setBeginTime(begin);
        voucher.setEndTime(end);
        voucherService.addSeckillVoucher(voucher);
        assertNotNull(voucher.getId());
        createdVouchers.add(voucher.getId());
        return voucher.getId();
    }

    private int stockOf(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        assertNotNull(voucher);
        return voucher.getStock();
    }

    private void login(long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }
}
