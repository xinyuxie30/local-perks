package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SECKILL_VOUCHER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    private static final int MAX_STOCK = 1_000_000;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopMapper shopMapper;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        if (shopId == null || shopId <= 0) {
            return Result.fail("店铺id不合法");
        }
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 秒杀券展示 Redis 实时库存：DB 库存要等订单落库才变，列表页会滞后甚至显示有货但抢不到
        for (Voucher voucher : vouchers) {
            if (voucher.getType() == null || voucher.getType() != 1) {
                continue;
            }
            try {
                String realTime = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucher.getId());
                if (realTime != null) {
                    voucher.setStock(Integer.parseInt(realTime));
                }
            } catch (Exception e) {
                // Redis 不可用时退回 DB 库存，不阻断列表展示
                log.warn("读取秒杀券实时库存失败，voucherId={}，使用 DB 库存", voucher.getId(), e);
            }
        }
        return Result.ok(vouchers);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSeckillVoucher(Voucher voucher) {
        validateSeckillVoucher(voucher);
        // 1.保存优惠券
        save(voucher);
        // 2.保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setTotalStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 3.事务提交后再写 Redis：避免 DB 回滚但库存已预热
        warmUpAfterCommit(voucher.getId(), voucher.getStock(), voucher.getBeginTime(), voucher.getEndTime());
    }

    private void validateSeckillVoucher(Voucher voucher) {
        if (voucher == null) {
            throw new IllegalArgumentException("优惠券信息不能为空");
        }
        if (voucher.getShopId() == null || voucher.getShopId() <= 0) {
            throw new IllegalArgumentException("店铺id不能为空");
        }
        Shop shop = shopMapper.selectById(voucher.getShopId());
        if (shop == null) {
            throw new IllegalArgumentException("店铺不存在");
        }
        if (voucher.getStock() == null || voucher.getStock() <= 0 || voucher.getStock() > MAX_STOCK) {
            throw new IllegalArgumentException("库存必须大于 0 且不超过 " + MAX_STOCK);
        }
        LocalDateTime begin = voucher.getBeginTime();
        LocalDateTime end = voucher.getEndTime();
        if (begin == null || end == null) {
            throw new IllegalArgumentException("活动开始与结束时间不能为空");
        }
        if (!begin.isBefore(end)) {
            throw new IllegalArgumentException("活动开始时间必须早于结束时间");
        }
        if (voucher.getPayValue() == null || voucher.getPayValue() < 0
                || voucher.getActualValue() == null || voucher.getActualValue() < 0) {
            throw new IllegalArgumentException("券金额不能为负数");
        }
        if (voucher.getPayValue() > voucher.getActualValue()) {
            throw new IllegalArgumentException("支付金额不能大于抵扣金额");
        }
        if (voucher.getType() == null) {
            voucher.setType(1);
        }
        if (voucher.getStatus() == null) {
            voucher.setStatus(1);
        }
    }

    private void warmUpAfterCommit(Long voucherId, Integer stock, LocalDateTime begin, LocalDateTime end) {
        Runnable action = () -> warmUp(voucherId, stock, begin, end);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * 预热失败不回滚已提交的券：管理端可以再次调用预热接口修复
     */
    public void warmUp(Long voucherId, Integer stock, LocalDateTime begin, LocalDateTime end) {
        try {
            stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, String.valueOf(stock));
            if (begin != null && end != null) {
                java.util.Map<String, String> value = new java.util.HashMap<>(2);
                value.put("beginTime", begin.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                value.put("endTime", end.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                stringRedisTemplate.opsForHash().putAll(CACHE_SECKILL_VOUCHER_KEY + voucherId, value);
                stringRedisTemplate.expire(CACHE_SECKILL_VOUCHER_KEY + voucherId, 5, java.util.concurrent.TimeUnit.MINUTES);
            }
        } catch (Exception e) {
            log.error("秒杀券预热失败，voucherId={}，请调用管理端预热接口重试", voucherId, e);
        }
    }
}
