package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "app.admin")
public class AdminProperties {

    /**
     * 管理端用户 id 白名单。为空表示没有任何管理员，写接口默认全部拒绝（fail-closed）
     */
    private Set<Long> userIds = new HashSet<>();
}
