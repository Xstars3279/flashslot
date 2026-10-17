package com.flashslot.messaging.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashslot.messaging.entity.DeadLetter;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DeadLetterMapper extends BaseMapper<DeadLetter> {
    @Insert("""
        INSERT INTO dead_letter(message_hash,body,event_id,event_type,error_code,received_at,
          source_queue,original_exchange,routing_key,failure_category,failure_reason,broker_death_count,last_received_at)
        VALUES (#{messageHash},#{body},#{eventId},#{eventType},#{errorCode},#{receivedAt},
          #{sourceQueue},#{originalExchange},#{routingKey},#{failureCategory},#{failureReason},#{brokerDeathCount},#{lastReceivedAt})
        ON DUPLICATE KEY UPDATE deliveries=deliveries+1,status='FAILED',recovered_at=NULL,
          failure_category=VALUES(failure_category),failure_reason=VALUES(failure_reason),
          broker_death_count=GREATEST(broker_death_count,VALUES(broker_death_count)),last_received_at=VALUES(last_received_at)
        """)
    void capture(DeadLetter letter);
    @Select("SELECT * FROM dead_letter WHERE id=#{id} FOR UPDATE")
    DeadLetter lock(long id);
}
