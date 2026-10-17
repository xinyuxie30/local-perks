package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;
    /**
     * 同一用户的订单处理锁，保证消息串行落库
     */
    public static final String LOCK_ORDER_KEY = "lock:order:";

    /**
     * 秒杀剩余可售库存，入口预扣，单位个
     */
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /**
     * 已占用购买资格的用户集合，一人一单第一层防线
     */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    /**
     * 事务消息回查标记，仅模式 A 写入，值 COMMIT 表示库存已预扣、半消息应当提交
     */
    public static final String SECKILL_TX_KEY = "seckill:tx:";
    public static final String SECKILL_TX_COMMIT = "COMMIT";
    /**
     * 事务消息回查窗口，单位秒
     */
    public static final Long SECKILL_TX_TTL = 1800L;
    /**
     * 排队状态，订单落库前供前端轮询，单位分钟
     */
    public static final String SECKILL_QUEUE_KEY = "seckill:queue:";
    public static final String SECKILL_QUEUE_PENDING = "PENDING";
    public static final String SECKILL_QUEUE_FAILED = "FAILED";
    public static final Long SECKILL_QUEUE_TTL = 10L;
    /**
     * 秒杀券活动时间窗缓存，单位分钟
     */
    public static final String CACHE_SECKILL_VOUCHER_KEY = "cache:seckill:voucher:";
    public static final Long CACHE_SECKILL_VOUCHER_TTL = 5L;

    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    /**
     * 评论点赞集合
     */
    public static final String COMMENT_LIKED_KEY = "comment:liked:";
    /**
     * 店铺缓存的跨实例失效广播频道，消息体为店铺id
     */
    public static final String SHOP_CACHE_INVALIDATE_TOPIC = "cache:invalidate:shop";
    /**
     * 限流键前缀
     */
    public static final String RATE_LIMIT_KEY = "rate:limit:";
    /**
     * 店铺布隆过滤器，拦截不存在的 id，减轻缓存穿透
     */
    public static final String SHOP_BLOOM_KEY = "bloom:shop";
}
