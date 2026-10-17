package com.flashslot.booking.mapper;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

@Mapper
public interface BookingExpiryMapper {
    /** 两类状态分别使用到期索引，越过本轮已尝试项，包括失败项。 */
    @Select(
            """
            SELECT id,due_at FROM (
                (SELECT id,check_in_deadline AS due_at FROM booking_order
                 WHERE status='CONFIRMED' AND check_in_deadline<=#{now}
                   AND (#{afterTime,jdbcType=TIMESTAMP} IS NULL OR check_in_deadline>#{afterTime}
                     OR (check_in_deadline=#{afterTime} AND id>#{afterId}))
                 ORDER BY check_in_deadline,id LIMIT 100)
                UNION ALL
                (SELECT id,end_at AS due_at FROM booking_order
                 WHERE status='CHECKED_IN' AND end_at<=#{now}
                   AND (#{afterTime,jdbcType=TIMESTAMP} IS NULL OR end_at>#{afterTime}
                     OR (end_at=#{afterTime} AND id>#{afterId}))
                 ORDER BY end_at,id LIMIT 100)
            ) due_orders ORDER BY due_at,id LIMIT 100
            """)
    List<com.flashslot.common.model.DueItem> due(@Param("now") Instant now,
        @Param("afterTime") Instant afterTime,@Param("afterId") long afterId);
}
