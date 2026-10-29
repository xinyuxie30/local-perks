package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** MQ 只携带事件标识，库存与订单信息由消费者重新查询持久化事件。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockRestoreMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    private String eventId;
}
