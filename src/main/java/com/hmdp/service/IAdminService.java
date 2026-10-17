package com.hmdp.service;

import com.hmdp.dto.Result;

public interface IAdminService {

    /**
     * 预热秒杀库存与时间窗。force=true 时以数据库库存覆盖 Redis
     */
    Result warmUpSeckill(Long voucherId, boolean force);

    /**
     * 秒杀活动观测：DB/Redis 库存、占用人数、订单状态分布
     */
    Result seckillStats(Long voucherId);

    /**
     * 手动触发对账，方便测试与故障恢复
     */
    Result reconcile();

    /**
     * 本地缓存命中率等指标
     */
    Result cacheStats();
}
