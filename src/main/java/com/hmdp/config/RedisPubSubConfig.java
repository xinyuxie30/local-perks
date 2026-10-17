package com.hmdp.config;

import com.hmdp.cache.ShopLocalCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

import static com.hmdp.utils.RedisConstants.SHOP_CACHE_INVALIDATE_TOPIC;

/**
 * Redis Pub/Sub：跨实例广播本地缓存失效，保证多节点下 L1 最终一致
 */
@Slf4j
@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer shopCacheInvalidateContainer(
            RedisConnectionFactory connectionFactory, ShopLocalCache shopLocalCache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            try {
                Long shopId = Long.valueOf(body.trim());
                shopLocalCache.invalidate(shopId);
                log.debug("收到店铺缓存失效广播，shopId={}", shopId);
            } catch (NumberFormatException e) {
                log.warn("无法解析店铺缓存失效消息：{}", body);
            }
        }, new ChannelTopic(SHOP_CACHE_INVALIDATE_TOPIC));
        return container;
    }
}
