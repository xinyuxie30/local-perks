package com.hmdp.cache;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_BLOOM_KEY;

/**
 * 店铺 id 布隆过滤器，挡在不存在的 id 之前，减轻缓存穿透。
 * Redis 不可用时 fail-open：放行到空值缓存链路，绝不因为过滤器故障误伤真实店铺。
 */
@Slf4j
@Component
public class ShopBloomFilter {

    private static final long EXPECTED_INSERTIONS = 10_000L;
    private static final double FALSE_POSITIVE_RATE = 0.01D;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ShopMapper shopMapper;

    private volatile boolean available = false;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            RBloomFilter<Long> filter = filter();
            filter.tryInit(EXPECTED_INSERTIONS, FALSE_POSITIVE_RATE);
            List<Shop> shops = shopMapper.selectList(null);
            for (Shop shop : shops) {
                filter.add(shop.getId());
            }
            available = true;
            log.info("店铺布隆过滤器就绪，已加载 {} 个店铺，Redis 计数={}", shops.size(), filter.count());
        } catch (Exception e) {
            available = false;
            log.warn("店铺布隆过滤器初始化失败，降级为不过滤：{}", e.getMessage());
        }
    }

    /**
     * 返回 false 表示这个 id 一定不存在，可以直接拒绝
     */
    public boolean mightContain(Long shopId) {
        if (!available || shopId == null) {
            return true;
        }
        try {
            return filter().contains(shopId);
        } catch (Exception e) {
            log.warn("布隆过滤器查询失败，本次放行：shopId={}，reason={}", shopId, e.getMessage());
            return true;
        }
    }

    public void add(Long shopId) {
        if (!available || shopId == null) {
            return;
        }
        try {
            filter().add(shopId);
        } catch (Exception e) {
            log.warn("布隆过滤器写入失败，shopId={}，reason={}", shopId, e.getMessage());
        }
    }

    private RBloomFilter<Long> filter() {
        return redissonClient.getBloomFilter(SHOP_BLOOM_KEY);
    }
}
