package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.cache")
public class LocalCacheProperties {

    /**
     * 本地缓存最大条目数，按条目数而不是字节数限制，避免堆内存无界增长
     */
    private long shopMaxSize = 10_000;

    /**
     * 本地缓存写入后过期时间，单位秒。跨实例失效走 Pub/Sub，TTL 只是兜底
     */
    private long shopTtlSeconds = 300;
}
