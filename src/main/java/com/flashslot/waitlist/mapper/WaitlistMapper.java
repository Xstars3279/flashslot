package com.flashslot.waitlist.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashslot.waitlist.entity.WaitlistRecord;
import com.flashslot.waitlist.vo.WaitlistView;

import org.apache.ibatis.annotations.*;

import java.time.*;
import java.util.List;

/** 候补记录由联合唯一键保证有效区间不重复，FIFO 使用日历锁内分配的 ID。 */
@Mapper
public interface WaitlistMapper extends BaseMapper<WaitlistRecord> {
    @Select("SELECT * FROM waitlist_record WHERE waitlist_no=#{number}")
    WaitlistRecord locate(String number);

    @Select("SELECT * FROM waitlist_record WHERE id=#{id} FOR UPDATE")
    WaitlistRecord lock(long id);

    @Select("SELECT id FROM waitlist_record WHERE status='WAITING' AND expire_at<=#{now} ORDER BY expire_at,id LIMIT 100")
    List<Long> findExpired(Instant now);

    @Update("UPDATE waitlist_record SET status=#{status},reason=#{reason} WHERE id=#{id} AND status='WAITING'")
    int finish(@Param("id") long id, @Param("status") String status, @Param("reason") String reason);
    String PROJECTION =
            """
w.waitlist_no,w.resource_id,w.book_date,w.start_slot,w.end_slot,w.purpose,w.status,w.reason,
w.expire_at,w.created_at,w.updated_at,r.name AS resource_name,r.location AS resource_location,
b.order_no AS matched_order_no,
CASE WHEN w.status='WAITING' AND w.expire_at>#{now} THEN
  (SELECT COUNT(*) FROM waitlist_record q WHERE q.resource_id=w.resource_id AND q.book_date=w.book_date
   AND q.start_slot=w.start_slot AND q.end_slot=w.end_slot AND q.status='WAITING' AND q.expire_at>#{now} AND q.id &lt;= w.id)
  ELSE NULL END AS queue_position
""";
    String TABLES =
            """
            FROM waitlist_record w JOIN resource r ON r.id=w.resource_id
            LEFT JOIN booking_order b ON b.id=w.matched_booking_id
            """;

    /** 用户行锁内统计未过期待处理数量，覆盖跨设备的每日上限。 */
    @Select(
            "SELECT COUNT(*) FROM waitlist_record WHERE user_id=#{userId} AND book_date=#{date} AND"
                + " status='WAITING' AND expire_at>#{now}")
    int waitingCount(
            @Param("userId") long userId, @Param("date") LocalDate date, @Param("now") Instant now);

    @Select({
        "<script>",
        "SELECT " + PROJECTION + TABLES + " WHERE w.waitlist_no=#{number} AND w.user_id=#{userId}",
        "</script>"
    })
    WaitlistView findOwn(
            @Param("number") String number,
            @Param("userId") long userId,
            @Param("now") Instant now);

    @Select(
            "SELECT id,created_at FROM waitlist_record WHERE waitlist_no=#{number} AND"
                + " user_id=#{userId}")
    WaitlistRecord findOwnCursor(@Param("number") String number, @Param("userId") long userId);

    /** 与订单相同的双字段倒序游标，不允许用别人的候补作为翻页锚点。 */
    @Select({
        "<script>",
        "SELECT " + PROJECTION + TABLES + " WHERE w.user_id=#{userId}",
        "<if test='anchor != null'>AND (w.created_at &lt; #{anchor.createdAt} OR"
            + " (w.created_at=#{anchor.createdAt} AND w.id &lt; #{anchor.id}))</if>",
        "ORDER BY w.created_at DESC,w.id DESC LIMIT #{fetchSize}",
        "</script>"
    })
    List<WaitlistView> findOwnPage(
            @Param("userId") long userId,
            @Param("anchor") WaitlistRecord anchor,
            @Param("fetchSize") int fetchSize,
            @Param("now") Instant now);
}
