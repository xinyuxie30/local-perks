package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Data
@Component
@ConfigurationProperties(prefix = "trade.stock-restore")
public class StockRestoreProperties {
    /** 仅暂停扫描发送；已经进入 MQ 的事件仍可被消费。 */
    private boolean enabled = true;
    private int batchSize = 50;
    private long scanDelayMs = 5000;
    private int leaseSeconds = 30;
    private int sendTimeoutMs = 3000;
    private int ackTimeoutSeconds = 120;
    private int retryBaseSeconds = 5;
    private int retryMaxSeconds = 300;
    private int maxSendAttempts = 20;
    private int alertAgeSeconds = 600;
    private long alertDelayMs = 60000;

    @PostConstruct
    public void validate() {
        if (batchSize < 1 || batchSize > 1000 || scanDelayMs < 1 || sendTimeoutMs < 1
                || leaseSeconds < 1 || leaseSeconds * 1000L <= sendTimeoutMs
                || ackTimeoutSeconds < 1 || retryBaseSeconds < 1
                || retryMaxSeconds < retryBaseSeconds || maxSendAttempts < 1
                || alertAgeSeconds < 1 || alertDelayMs < 1) {
            throw new IllegalArgumentException("Invalid trade.stock-restore settings");
        }
    }
}
