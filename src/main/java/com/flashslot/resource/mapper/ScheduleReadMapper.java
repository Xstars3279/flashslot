package com.flashslot.resource.mapper;

import com.flashslot.resource.model.ScheduleOccupation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 受控跨表只读查询，不在资源模块直接修改订单、候补或占用表。 */
@Mapper
public interface ScheduleReadMapper {
    /** 只投影本人订单信息，用于覆盖共享缓存中的公开状态。 */
    @Select("SELECT s.slot_index,s.state,b.order_no AS own_order_no FROM slot_occupation s JOIN booking_order b ON b.id=s.booking_id WHERE s.resource_id=#{resourceId} AND s.book_date=#{date} AND b.user_id=#{viewerId}")
    List<ScheduleOccupation> findOwnOccupations(@Param("resourceId") long resourceId,
        @Param("date") LocalDate date,@Param("viewerId") long viewerId);
    /** 查询设备当天占用；viewerId 为空时不能返回任何订单号。 */
    @Select("""
        SELECT s.slot_index,s.state,
          CASE WHEN b.user_id=#{viewerId,jdbcType=BIGINT} THEN b.order_no ELSE NULL END AS own_order_no
        FROM slot_occupation s LEFT JOIN booking_order b ON b.id=s.booking_id
        WHERE s.resource_id=#{resourceId} AND s.book_date=#{date} ORDER BY s.slot_index
        """)
    List<ScheduleOccupation> findOccupations(@Param("resourceId") long resourceId,
        @Param("date") LocalDate date, @Param("viewerId") Long viewerId);

    /**
     * 判断关闭/缩短日历是否会影响有效业务；须先持有资源日排他锁。
     * now 是取得业务锁后的判定时间，firstSlot 包括今天正在进行的时间格。
     */
    @Select("""
        SELECT (
          EXISTS(SELECT 1 FROM booking_order WHERE resource_id=#{resourceId} AND book_date=#{date}
            AND status IN ('CONFIRMED','CHECKED_IN') AND end_at>#{now})
          OR EXISTS(SELECT 1 FROM waitlist_record WHERE resource_id=#{resourceId} AND book_date=#{date}
            AND status='WAITING' AND expire_at>#{now})
          OR EXISTS(SELECT 1 FROM slot_occupation WHERE resource_id=#{resourceId} AND book_date=#{date}
            AND state='HANDOFF' AND slot_index>=#{firstSlot})
        )
        """)
    boolean hasActiveUse(@Param("resourceId") long resourceId, @Param("date") LocalDate date,
        @Param("now") Instant now, @Param("firstSlot") int firstSlot);
}
