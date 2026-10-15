package com.flashslot.booking.mapper;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

@Mapper
public interface BookingExpiryMapper {
    /** 两类状态分别使用到期索引，合并后按到期时间、ID 取本轮最多 100 条。 */
    @Select(
            """
            SELECT id FROM (
                (SELECT id,check_in_deadline AS due_at FROM booking_order
                 WHERE status='CONFIRMED' AND check_in_deadline<=#{now}
                 ORDER BY check_in_deadline,id LIMIT 100)
                UNION ALL
                (SELECT id,end_at AS due_at FROM booking_order
                 WHERE status='CHECKED_IN' AND end_at<=#{now}
                 ORDER BY end_at,id LIMIT 100)
            ) due_orders ORDER BY due_at,id LIMIT 100
            """)
    List<Long> due(Instant now);
}
