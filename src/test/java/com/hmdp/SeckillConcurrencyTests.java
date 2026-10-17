package com.hmdp;

import com.hmdp.config.SeckillProperties;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需要 MySQL、Redis、RocketMQ 都在运行，且已导入 db/V2__trade.sql。
 * 测试会写入数据，请使用独立的开发库
 */
@SpringBootTest
@ActiveProfiles("test")
class SeckillConcurrencyTests {

    private static final int STOCK = 10;
    private static final int THREADS = 100;
    private static final long FIRST_USER_ID = 90000L;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillProperties seckillProperties;

    private Long voucherId;

    @AfterEach
    void cleanUp() {
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

    @Test
    void concurrentSeckillShouldNotOversell() throws Exception {
        voucherId = createSeckillVoucher(STOCK);

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            final long userId = FIRST_USER_ID + i;
            pool.submit(() -> {
                try {
                    UserDTO user = new UserDTO();
                    user.setId(userId);
                    UserHolder.saveUser(user);
                    startGate.await();
                    Result result = voucherOrderService.seckillVoucher(voucherId);
                    if (Boolean.TRUE.equals(result.getSuccess())) {
                        accepted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    rejected.incrementAndGet();
                } finally {
                    UserHolder.removeUser();
                    done.countDown();
                }
            });
        }

        long begin = System.currentTimeMillis();
        startGate.countDown();
        assertTrue(done.await(180, TimeUnit.SECONDS), "抢购线程未在超时时间内结束");
        pool.shutdown();
        System.out.println("抢购耗时 " + (System.currentTimeMillis() - begin) + "ms，入口受理 "
                + accepted.get() + "，入口拒绝 " + rejected.get());

        if (seckillProperties.isClaimInConsumer()) {
            // 模式 B 入口不判断资格，所有请求都会被受理，胜负由消费者认领决定
            assertEquals(THREADS, accepted.get());
        } else {
            // 模式 A 在入口用 Lua 判定，只有库存份数的请求能拿到订单号
            assertEquals(STOCK, accepted.get(), "入口放行的请求数应等于库存数");
        }

        assertEquals(STOCK, awaitOrders(120_000L), "落库订单数应等于库存数，多一单就是超卖");

        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        assertEquals(0, voucher.getStock().intValue(), "MySQL 库存应扣到 0");
        assertEquals("0", stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId),
                "Redis 库存应扣到 0");

        Integer distinctUsers = voucherOrderMapper.countDistinctUser(voucherId);
        assertEquals(STOCK, distinctUsers.intValue(), "一个用户只能有一张券");
        Set<String> occupied = stringRedisTemplate.opsForSet().members(SECKILL_ORDER_KEY + voucherId);
        assertEquals(STOCK, occupied.size(), "占用集合成员数应等于库存数");
    }

    @Test
    void repeatedPayShouldOnlyTakeEffectOnce() {
        voucherId = createSeckillVoucher(1);
        long orderId = 999L;
        long userId = FIRST_USER_ID;

        SeckillOrderMessage message = new SeckillOrderMessage(orderId, userId, voucherId);
        voucherOrderService.createOrder(message);

        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
        try {
            assertTrue(voucherOrderService.payOrder(orderId, 1).getSuccess(), "首次支付应当成功");
            Result second = voucherOrderService.payOrder(orderId, 3);
            assertFalse(second.getSuccess(), "重复支付应被条件更新挡下");
            assertEquals("订单状态已变更，请刷新后重试", second.getErrorMsg());

            // 重复投递同一条消息不应产生第二张券
            voucherOrderService.createOrder(message);
            assertEquals(1, voucherOrderService.query().eq("voucher_id", voucherId).count().intValue());
        } finally {
            UserHolder.removeUser();
        }
    }

    private Long createSeckillVoucher(int stock) {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("并发测试" + stock + "元券");
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

    /**
     * 订单是异步落库的，这里轮询等待消费者把消息处理完
     */
    private long awaitOrders(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            long count = voucherOrderService.query().eq("voucher_id", voucherId).count();
            if (count >= STOCK) {
                return count;
            }
            Thread.sleep(200);
        }
        return voucherOrderService.query().eq("voucher_id", voucherId).count();
    }
}
