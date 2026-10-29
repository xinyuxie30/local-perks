package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.StockRestoreEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 每次状态更新均是短数据库操作，不把 MQ/Redis 网络调用包进数据库事务。 */
public interface StockRestoreEventMapper extends BaseMapper<StockRestoreEvent> {
    String DUE = "((status IN ('PENDING','SENT') AND next_attempt_at <= CURRENT_TIMESTAMP) "
            + "OR (status = 'SENDING' AND locked_until <= CURRENT_TIMESTAMP))";

    @Select("SELECT * FROM tb_stock_restore_event WHERE " + DUE
            + " ORDER BY next_attempt_at, id LIMIT #{limit}")
    List<StockRestoreEvent> findDue(@Param("limit") int limit);

    @Update("UPDATE tb_stock_restore_event SET status='SENDING', lock_token=#{token}, "
            + "locked_until=DATE_ADD(CURRENT_TIMESTAMP, INTERVAL #{leaseSeconds} SECOND), "
            + "send_attempts=send_attempts+1 WHERE id=#{id} AND send_attempts < #{maxAttempts} AND " + DUE)
    int claim(@Param("id") String id, @Param("token") String token,
              @Param("leaseSeconds") int leaseSeconds, @Param("maxAttempts") int maxAttempts);

    @Update("UPDATE tb_stock_restore_event SET status='SENT', sent_at=CURRENT_TIMESTAMP, "
            + "next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP, INTERVAL #{ackTimeoutSeconds} SECOND), "
            + "lock_token=NULL, locked_until=NULL WHERE id=#{id} AND status='SENDING' AND lock_token=#{token}")
    int markSent(@Param("id") String id, @Param("token") String token,
                 @Param("ackTimeoutSeconds") int ackTimeoutSeconds);

    @Update("UPDATE tb_stock_restore_event SET status='PENDING', last_error=#{error}, "
            + "next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP, INTERVAL #{retrySeconds} SECOND), "
            + "lock_token=NULL, locked_until=NULL WHERE id=#{id} AND status='SENDING' AND lock_token=#{token}")
    int markSendFailure(@Param("id") String id, @Param("token") String token,
                        @Param("retrySeconds") int retrySeconds, @Param("error") String error);

    @Update("UPDATE tb_stock_restore_event SET status='FAILED', lock_token=NULL, locked_until=NULL, "
            + "last_error=COALESCE(last_error, 'send attempts exhausted without consumption confirmation') "
            + "WHERE id=#{id} AND send_attempts >= #{maxAttempts} AND " + DUE)
    int markExhausted(@Param("id") String id, @Param("maxAttempts") int maxAttempts);

    @Update("UPDATE tb_stock_restore_event SET status='DONE', completed_at=CURRENT_TIMESTAMP, "
            + "consume_attempts=consume_attempts+1, last_error=NULL, lock_token=NULL, locked_until=NULL "
            + "WHERE id=#{id} AND status <> 'DONE'")
    int markDone(@Param("id") String id);

    @Update("UPDATE tb_stock_restore_event SET consume_attempts=consume_attempts+1, last_error=#{error} "
            + "WHERE id=#{id} AND status <> 'DONE'")
    int recordConsumeFailure(@Param("id") String id, @Param("error") String error);

    @Select("SELECT COUNT(*) FROM tb_stock_restore_event WHERE voucher_id=#{voucherId} AND status <> 'DONE'")
    long countIncompleteForVoucher(@Param("voucherId") Long voucherId);

    @Select("SELECT COUNT(*) FROM tb_stock_restore_event WHERE status='FAILED'")
    long countFailed();

    @Select("SELECT COUNT(*) FROM tb_stock_restore_event WHERE status <> 'DONE' "
            + "AND created_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL #{ageSeconds} SECOND)")
    long countOverdue(@Param("ageSeconds") int ageSeconds);

    @Select("SELECT * FROM tb_stock_restore_event WHERE status <> 'DONE' ORDER BY created_at, id LIMIT 1")
    StockRestoreEvent findOldestIncomplete();
}
