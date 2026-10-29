package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.SeckillProperties;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.SeckillOrderProducer;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.StockRestoreOutboxService;
import com.hmdp.utils.OrderStatus;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.OrderStatusVO;
import com.hmdp.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StockRestoreOutboxService stockRestoreOutboxService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private SeckillProperties seckillProperties;

    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券id不合法");
        }
        Long userId = user.getId();

        // 1.校验活动时间窗，别让 begin_time / end_time 只躺在数据库里
        Result windowResult = checkSeckillWindow(voucherId);
        if (windowResult != null) {
            return windowResult;
        }

        // 2.生成全局唯一订单id，同时作为订单主键，重复投递会撞主键
        long orderId = redisIdWorker.nextId("order");
        // 3.尚未发送的临时标记有 TTL；模式 A 的 Lua 在库存预扣成功时原子 PERSIST。
        // 已受理的排队状态由消费者终态清理，不能因排队超过十分钟而消失。
        // 值里带上 userId（PENDING:<userId>），供状态查询接口做归属校验
        stringRedisTemplate.opsForValue().set(SECKILL_QUEUE_KEY + orderId,
                SECKILL_QUEUE_PENDING + ":" + userId,
                SECKILL_QUEUE_TTL, TimeUnit.MINUTES);

        if (seckillProperties.isClaimInConsumer()) {
            stringRedisTemplate.persist(SECKILL_QUEUE_KEY + orderId);
            // 模式 B：入口不碰库存，只排队投递，库存扣减下移到消费者
            try {
                seckillOrderProducer.sendOrderMessage(new SeckillOrderMessage(orderId, userId, voucherId));
            } catch (Exception e) {
                // 投递失败必须清掉排队标记，否则前端会一直轮询到 TTL 过期
                stringRedisTemplate.delete(SECKILL_QUEUE_KEY + orderId);
                log.error("秒杀消息投递失败，orderId={}", orderId, e);
                return Result.fail("系统繁忙，请稍后重试");
            }
            return Result.ok(orderIdText(orderId));
        }

        // 模式 A：事务消息。本地事务里执行 Lua 预扣，把「库存已扣」与「消息最终可消费」绑在一起
        TransactionSendResult sendResult;
        try {
            sendResult = seckillOrderProducer
                    .sendOrderMessageInTransaction(new SeckillOrderMessage(orderId, userId, voucherId));
        } catch (Exception e) {
            stringRedisTemplate.delete(SECKILL_QUEUE_KEY + orderId);
            log.error("秒杀事务消息发送失败，orderId={}", orderId, e);
            return Result.fail("系统繁忙，请稍后重试");
        }
        LocalTransactionState state = sendResult == null ? null : sendResult.getLocalTransactionState();
        if (LocalTransactionState.COMMIT_MESSAGE.equals(state)) {
            return Result.ok(orderIdText(orderId));
        }
        // RocketMQ 的枚举拼写就是 UNKNOW（少一个 N），不是笔误
        if (LocalTransactionState.UNKNOW.equals(state)) {
            // Redis 瞬时故障或 Broker 尚未回查：半消息可能稍后提交，继续让前端轮询，不能误报失败
            log.warn("秒杀本地事务状态未知，订单可能稍后落库，orderId={}", orderId);
            return Result.ok(orderIdText(orderId));
        }
        // 本地事务回滚：Lua 没有扣减，排队标记已被监听器改写为失败原因
        String failed = stringRedisTemplate.opsForValue().get(SECKILL_QUEUE_KEY + orderId);
        return Result.fail(describeFailure(failed));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createOrder(SeckillOrderMessage message) {
        if (message == null || message.getOrderId() == null
                || message.getUserId() == null || message.getVoucherId() == null) {
            // 脏消息直接确认，避免无限重试堵住队列
            log.error("秒杀消息字段不完整，丢弃：{}", message);
            return;
        }
        Long userId = message.getUserId();
        Long voucherId = message.getVoucherId();
        try {
            // 先插入订单，唯一索引负责并发幂等；不再先查 count 或持有 Redis 用户锁。
            // 最后才更新热点库存行，随后立即提交，缩短同券所有订单串行等待的区间。
            VoucherOrder order = new VoucherOrder();
            order.setId(message.getOrderId());
            order.setUserId(userId);
            order.setVoucherId(voucherId);
            order.setPayType(1);
            order.setStatus(OrderStatus.UNPAID);
            save(order);
            boolean deducted = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();
            if (!deducted) {
                // 让 MQ 重试，重试耗尽后进死信队列，由对账任务收口
                throw new IllegalStateException("数据库库存不足，voucherId=" + voucherId);
            }
        } catch (DuplicateKeyException e) {
            // 插入冲突时尚未扣库存，回滚本次事务；重投不能再次扣减。
            markRollbackOnly();
            log.info("订单已存在，幂等忽略并回滚本次扣减，orderId={}", message.getOrderId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean closeOrderIfUnpaid(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return false;
        }
        VoucherOrder order = getById(orderId);
        if (order == null || !Integer.valueOf(OrderStatus.UNPAID).equals(order.getStatus())) {
            return false;
        }
        // CAS：只有仍未支付才关闭，支付与关单并发时只有一个能改到行
        boolean closed = update()
                .set("status", OrderStatus.CANCELED)
                .eq("id", orderId)
                .eq("status", OrderStatus.UNPAID)
                .update();
        if (!closed) {
            return false;
        }
        restoreStock(order);
        log.info("订单超时关闭，MySQL 库存与 Redis 回补事件已写入当前事务，orderId={}，voucherId={}",
                orderId, order.getVoucherId());
        return true;
    }

    @Override
    public Result queryOrderStatus(Long orderId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        if (orderId == null || orderId <= 0) {
            return Result.fail("订单id不合法");
        }
        // 归属校验：订单状态只对下单人可见，防止凭订单 id 探测他人订单
        Long me = user.getId();
        OrderStatusVO vo = new OrderStatusVO();
        vo.setOrderId(orderId);
        VoucherOrder order = getById(orderId);
        if (order != null) {
            if (!order.getUserId().equals(me)) {
                return Result.fail("订单不存在");
            }
            vo.setState("CREATED");
            vo.setStatus(order.getStatus());
            return Result.ok(vo);
        }
        String queued = stringRedisTemplate.opsForValue().get(SECKILL_QUEUE_KEY + orderId);
        if (queued == null) {
            // 消费者可能恰好在第一次 SQL 查询之后提交并删除标记，必须再读一次。
            VoucherOrder committed = getById(orderId);
            if (committed != null) {
                if (!me.equals(committed.getUserId())) {
                    return Result.fail("订单不存在");
                }
                vo.setState("CREATED");
                vo.setStatus(committed.getStatus());
                return Result.ok(vo);
            }
            vo.setState("NOT_FOUND");
            return Result.ok(vo);
        }
        if (!queuedBelongsTo(queued, me)) {
            return Result.fail("订单不存在");
        }
        if (queued.startsWith(SECKILL_QUEUE_FAILED)) {
            vo.setState("FAILED");
            vo.setFailReason(describeFailure(queued));
        } else {
            vo.setState("PENDING");
        }
        return Result.ok(vo);
    }

    /**
     * 排队值格式：PENDING:&lt;userId&gt; / FAILED:&lt;userId&gt;:&lt;code&gt;。
     * 旧格式（不带 userId 段）一律视为不归属，安全优先
     */
    private boolean queuedBelongsTo(String queued, Long userId) {
        if (queued == null || userId == null) {
            return false;
        }
        String[] parts = queued.split(":");
        return parts.length >= 2 && parts[1].equals(String.valueOf(userId));
    }

    @Override
    public Result queryOrderDetail(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return Result.fail("订单id不合法");
        }
        OrderVO orderVO = getBaseMapper().queryOrderDetail(orderId, UserHolder.getUser().getId());
        if (orderVO == null) {
            return Result.fail("订单不存在");
        }
        return Result.ok(orderVO);
    }

    @Override
    public Result queryMyOrders(Integer current, Integer status) {
        if (current == null || current < 1 || current > SystemConstants.MAX_PAGE_NUMBER) {
            return Result.fail("页码不合法");
        }
        if (status != null && (status < OrderStatus.UNPAID || status > OrderStatus.REFUNDED)) {
            return Result.fail("订单状态不合法");
        }
        Page<OrderVO> page = new Page<>(current, SystemConstants.MAX_PAGE_SIZE);
        getBaseMapper().queryMyOrders(page, UserHolder.getUser().getId(), status);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    public Result payOrder(Long orderId, Integer payType) {
        if (orderId == null || orderId <= 0) {
            return Result.fail("订单id不合法");
        }
        int type = payType == null ? 1 : payType;
        if (type < 1 || type > 3) {
            return Result.fail("支付方式不合法");
        }
        Long userId = UserHolder.getUser().getId();
        boolean paid = update()
                .set("status", OrderStatus.PAID)
                .set("pay_type", type)
                .set("pay_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", OrderStatus.UNPAID)
                .update();
        if (!paid) {
            return Result.fail("订单状态已变更，请刷新后重试");
        }
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result cancelOrder(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return Result.fail("订单id不合法");
        }
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query().eq("id", orderId).eq("user_id", userId).one();
        if (order == null) {
            return Result.fail("订单不存在");
        }
        boolean canceled = update()
                .set("status", OrderStatus.CANCELED)
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", OrderStatus.UNPAID)
                .update();
        if (!canceled) {
            return Result.fail("只有未支付的订单可以取消");
        }
        restoreStock(order);
        return Result.ok();
    }

    @Override
    public Result useOrder(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return Result.fail("订单id不合法");
        }
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query().eq("id", orderId).eq("user_id", userId).one();
        if (order == null) {
            return Result.fail("订单不存在");
        }
        // 券过期后不能再核销，避免活动结束后还能用
        SeckillVoucher voucher = seckillVoucherService.getById(order.getVoucherId());
        if (voucher != null && voucher.getEndTime() != null
                && voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("优惠券已过期，无法核销");
        }
        boolean used = update()
                .set("status", OrderStatus.USED)
                .set("use_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", OrderStatus.PAID)
                .update();
        if (!used) {
            return Result.fail("只有已支付的订单可以核销");
        }
        return Result.ok();
    }

    @Override
    public Result refundOrder(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return Result.fail("订单id不合法");
        }
        Long userId = UserHolder.getUser().getId();
        // 退款不退还秒杀库存：券的额度已经消耗，退的是钱不是库存
        boolean refunded = update()
                .set("status", OrderStatus.REFUNDED)
                .set("refund_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", OrderStatus.PAID)
                .update();
        if (!refunded) {
            return Result.fail("只有已支付的订单可以退款");
        }
        return Result.ok();
    }

    /**
     * 订单id 由时间戳左移 32 位生成，约 5×10^17，超出 JS 安全整数范围，
     * 直接以数字返回给前端会被四舍五入，导致拿回来的 id 查不到订单
     */
    private String orderIdText(long orderId) {
        return String.valueOf(orderId);
    }

    /**
     * 回补 MySQL 并写入事务发件箱；Redis 由消息消费者异步回补。
     * 故意不把用户从 seckill:order 集合移除：
     * 终身一单语义下，取消/退款后该用户依旧不能再购买同一张券，与唯一索引保持一致
     */
    private void restoreStock(VoucherOrder order) {
        Long voucherId = order.getVoucherId();
        // 与订单 CAS、事件插入同一事务。条件直接使用库存行的当前上限，避免快照过时。
        boolean updated = seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", voucherId)
                .ge("stock", 0)
                .apply("stock < total_stock")
                .update();
        if (!updated) {
            // 不能提交“订单已取消但未回补/无事件”的半完成状态。
            throw new IllegalStateException("MySQL 库存回补失败，取消事务回滚，orderId="
                    + order.getId() + ", voucherId=" + voucherId);
        }
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        stockRestoreOutboxService.createEvent(order.getId(), voucherId, voucher.getTotalStock());
    }

    /**
     * 返回 null 表示时间窗校验通过
     */
    private Result checkSeckillWindow(Long voucherId) {
        String key = CACHE_SECKILL_VOUCHER_KEY + voucherId;
        Map<Object, Object> cached = stringRedisTemplate.opsForHash().entries(key);
        LocalDateTime beginTime = null;
        LocalDateTime endTime = null;
        boolean loaded = false;
        if (!cached.isEmpty()) {
            try {
                beginTime = parseTime(cached.get("beginTime"));
                endTime = parseTime(cached.get("endTime"));
                loaded = true;
            } catch (Exception e) {
                // 缓存格式损坏（例如手工改过 key），删掉回源，不能让请求 500
                log.warn("秒杀时间窗缓存损坏，重新加载：voucherId={}", voucherId);
                stringRedisTemplate.delete(key);
            }
        }
        if (!loaded) {
            SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
            if (voucher == null) {
                return Result.fail("优惠券不存在");
            }
            beginTime = voucher.getBeginTime();
            endTime = voucher.getEndTime();
            Map<String, String> value = new HashMap<>(2);
            value.put("beginTime", beginTime == null ? "" : beginTime.format(TIME_FORMATTER));
            value.put("endTime", endTime == null ? "" : endTime.format(TIME_FORMATTER));
            try {
                stringRedisTemplate.opsForHash().putAll(key, value);
                stringRedisTemplate.expire(key, CACHE_SECKILL_VOUCHER_TTL, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("秒杀时间窗写入缓存失败，本次直接放行：voucherId={}", voucherId);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        if (beginTime != null && beginTime.isAfter(now)) {
            return Result.fail("秒杀尚未开始");
        }
        if (endTime != null && endTime.isBefore(now)) {
            return Result.fail("秒杀已经结束");
        }
        return null;
    }

    private LocalDateTime parseTime(Object value) {
        if (value == null || value.toString().isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(value.toString(), TIME_FORMATTER);
    }

    private String describeFailure(String queuedState) {
        if (queuedState == null) {
            return "抢购失败，请稍后重试";
        }
        if (queuedState.endsWith(":1")) {
            return "库存不足";
        }
        if (queuedState.endsWith(":2")) {
            return "不能重复下单";
        }
        if (queuedState.endsWith(":3")) {
            return "优惠券不存在或未预热";
        }
        return "抢购失败，请稍后重试";
    }

    private void markRollbackOnly() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException e) {
            // 理论上不会走到这里：createOrder 由代理调用，事务一定处于激活状态
            log.warn("幂等回滚时发现事务未激活，可能出现库存多扣，交给对账任务收敛");
        }
    }
}
