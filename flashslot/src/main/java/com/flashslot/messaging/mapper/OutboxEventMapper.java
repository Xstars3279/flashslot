package com.flashslot.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.flashslot.messaging.entity.OutboxEvent;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;

/** 事件单主键基础操作由 MyBatis-Plus 提供。 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {
    /** 仅为仍 PENDING 的任务派发；1 秒容差覆盖 DATETIME(3) 写入时的小数秒舍入。 */
    @Insert("""
        INSERT INTO outbox_event(event_id,event_type,aggregate_id,payload,next_run_at,created_at)
        SELECT #{eventId},#{eventType},#{aggregateId},
          #{payload,typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler},#{nextRunAt},#{createdAt}
        FROM release_job WHERE id=#{aggregateId} AND status='PENDING'
          AND next_run_at<=DATE_ADD(#{createdAt},INTERVAL 1 SECOND)
        ON DUPLICATE KEY UPDATE event_id=outbox_event.event_id
        """)
    int insertReleaseDispatch(OutboxEvent event);

    List<String> findDue(@Param("now") Instant now, @Param("types") List<String> types);

    @Select("SELECT * FROM outbox_event WHERE event_id=#{eventId} FOR UPDATE")
    @Results(
            @Result(
                    column = "payload",
                    property = "payload",
                    typeHandler = JacksonTypeHandler.class))
    OutboxEvent lock(String eventId);

    @Update(
            "UPDATE outbox_event SET"
                + " status='SENDING',attempts=#{attempts},lease_token=#{leaseToken},lease_until=#{leaseUntil}"
                + " WHERE event_id=#{eventId}")
    int claim(OutboxEvent event);

    @Update(
            "UPDATE outbox_event SET"
                + " status='PUBLISHED',published_at=#{now},lease_token=NULL,lease_until=NULL,last_error=NULL"
                + " WHERE event_id=#{eventId} AND status='SENDING' AND lease_token=#{token}")
    int markPublished(
            @Param("eventId") String eventId,
            @Param("token") String token,
            @Param("now") Instant now);

    @Update(
            "UPDATE outbox_event SET"
                + " status=#{status},next_run_at=#{next},last_error=#{reason},lease_token=NULL,lease_until=NULL"
                + " WHERE event_id=#{eventId}")
    int finishFailure(
            @Param("eventId") String eventId,
            @Param("status") String status,
            @Param("next") Instant next,
            @Param("reason") String reason);
}
