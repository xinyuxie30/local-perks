package com.hmdp.config;

import org.redisson.Redisson;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(RedisProperties properties){
        // 配置
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + properties.getHost() + ":" + properties.getPort())
                .setPassword(properties.getPassword())
                .setDatabase(properties.getDatabase());
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
