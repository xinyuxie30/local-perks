package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    /**
     * 总开关，压测或排障时可以关闭
     */
    private boolean enabled = true;

    /**
     * Redis 不可用时是否放行。业务接口优先可用性，默认放行并打告警日志
     */
    private boolean failOpen = true;
}
