package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "seckill")
public class SeckillProperties {

    /**
     * A：入口执行 Lua 预扣库存，并通过 RocketMQ 事务消息投递下单消息
     * B：入口只做排队，库存扣减与一人一单下移到消费者侧执行
     */
    private String mode = "A";

    public boolean isClaimInConsumer() {
        return "B".equalsIgnoreCase(mode);
    }
}
