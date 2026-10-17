package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.cache.ShopBloomFilter;
import com.hmdp.cache.ShopCacheInvalidationPublisher;
import com.hmdp.cache.ShopLocalCache;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private static final double MAX_LATITUDE = 90D;
    private static final double MAX_LONGITUDE = 180D;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopLocalCache shopLocalCache;

    @Resource
    private ShopCacheInvalidationPublisher invalidationPublisher;

    @Resource
    private ShopBloomFilter shopBloomFilter;

    @Override
    public Result queryById(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("店铺id不合法");
        }
        // L0：布隆过滤器，挡掉一定不存在的 id，避免打穿到 Redis/DB
        if (!shopBloomFilter.mightContain(id)) {
            return Result.fail("店铺不存在！");
        }
        // L1：进程内 Caffeine，命中直接返回
        Shop local = shopLocalCache.get(id);
        if (local != null) {
            return Result.ok(local);
        }
        // L2：Redis 空值缓存 + 穿透兜底；未命中再查 DB
        Shop shop = cacheClient.queryWithPassThrough(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        shopLocalCache.put(id, shop);
        return Result.ok(shop);
    }

    @Override
    public boolean save(Shop shop) {
        if (shop == null || shop.getTypeId() == null || StrUtil.isBlank(shop.getName())) {
            throw new IllegalArgumentException("店铺名称和类型不能为空");
        }
        boolean saved = super.save(shop);
        if (saved) {
            // 新店铺必须同步进布隆过滤器，否则重启前会被误判为不存在
            shopBloomFilter.add(shop.getId());
        }
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        Long id = shop == null ? null : shop.getId();
        if (id == null || id <= 0) {
            return Result.fail("店铺id不能为空");
        }
        if (getById(id) == null) {
            return Result.fail("店铺不存在");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.事务提交后再删缓存，避免"删缓存成功、事务回滚"导致缓存被旧值重新填满
        afterCommit(() -> invalidate(id));
        shopBloomFilter.add(id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y, String sortBy) {
        if (typeId == null || typeId <= 0) {
            return Result.fail("店铺类型不合法");
        }
        if (current == null || current < 1 || current > SystemConstants.MAX_PAGE_NUMBER) {
            return Result.fail("页码不合法");
        }
        if ((x == null) != (y == null)) {
            return Result.fail("经度和纬度必须同时提供");
        }

        // 人气/评分排序走 DB 分页；距离排序仍走 GEO 半径查询
        String sortField = normalizeSortField(sortBy);
        if (sortField != null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .orderByDesc(sortField)
                    .orderByDesc("id")
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        // 1.判断是否需要根据坐标查询
        if (x == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        if (x < -MAX_LONGITUDE || x > MAX_LONGITUDE || y < -MAX_LATITUDE || y > MAX_LATITUDE) {
            return Result.fail("坐标不合法");
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr, result.getDistance());
        });
        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance.getValue());
            }
        }
        // 6.返回
        return Result.ok(shops);
    }

    /**
     * 白名单式排序字段：前端参数绝不直接拼进 ORDER BY
     */
    private String normalizeSortField(String sortBy) {
        if (StrUtil.isBlank(sortBy)) {
            return null;
        }
        switch (sortBy.trim()) {
            case "comments":
                return "comments";
            case "score":
                return "score";
            case "sold":
                return "sold";
            default:
                return null;
        }
    }

    private void invalidate(Long id) {
        try {
            stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        } catch (Exception e) {
            log.warn("删除店铺 Redis 缓存失败，shopId={}，reason={}", id, e.getMessage());
        }
        shopLocalCache.invalidate(id);
        invalidationPublisher.publish(id);
    }

    /**
     * 有事务时挂到提交后执行，无事务时立即执行
     */
    private void afterCommit(Runnable action) {
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
}
