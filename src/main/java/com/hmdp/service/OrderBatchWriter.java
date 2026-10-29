package com.hmdp.service;

import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.utils.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One durable transaction per bounded batch; any conflict rolls back the entire batch. */
@Service
public class OrderBatchWriter {
    @Resource private IVoucherOrderService orders;
    @Resource private ISeckillVoucherService vouchers;

    @Transactional(rollbackFor = Exception.class)
    public void write(List<SeckillOrderMessage> messages) {
        if (messages.isEmpty() || messages.size() > 32) throw new IllegalArgumentException("Batch size must be 1..32");
        List<VoucherOrder> rows = new ArrayList<>(messages.size());
        Map<Long, Integer> quantities = new TreeMap<>();
        for (SeckillOrderMessage message : messages) {
            if (message == null || message.getOrderId() == null || message.getUserId() == null || message.getVoucherId() == null)
                throw new IllegalArgumentException("Invalid order message");
            VoucherOrder row = new VoucherOrder();
            row.setId(message.getOrderId());
            row.setUserId(message.getUserId());
            row.setVoucherId(message.getVoucherId());
            row.setPayType(1);
            row.setStatus(OrderStatus.UNPAID);
            rows.add(row);
            quantities.merge(message.getVoucherId(), 1, Integer::sum);
        }
        if (!orders.saveBatch(rows, rows.size())) throw new IllegalStateException("Batch insert failed");
        // A stable voucher order prevents cross-voucher lock inversion.
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            int count = entry.getValue();
            if (!vouchers.update().setSql("stock = stock - " + count)
                    .eq("voucher_id", entry.getKey()).ge("stock", count).update())
                throw new IllegalStateException("Insufficient stock for batch, voucherId=" + entry.getKey());
        }
    }
}
