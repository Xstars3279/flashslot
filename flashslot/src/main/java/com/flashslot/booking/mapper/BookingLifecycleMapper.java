package com.flashslot.booking.mapper;

import com.flashslot.booking.entity.BookingOrder;

import org.apache.ibatis.annotations.*;

import java.time.*;

/** 订单锁定及精确归属的占用变更，不提供按用户批量删除。 */
@Mapper
public interface BookingLifecycleMapper {
    @Select("SELECT * FROM booking_order WHERE order_no=#{orderNo}")
    BookingOrder locate(String orderNo);

    @Select("SELECT * FROM booking_order WHERE id=#{id} FOR UPDATE")
    BookingOrder lock(long id);

    @Select("SELECT * FROM booking_order WHERE id=#{id}")
    BookingOrder find(long id);

    @Update(
            "UPDATE booking_order SET status='CHECKED_IN',checked_in_at=#{now},version=version+1"
                + " WHERE id=#{id} AND version=#{version} AND status='CONFIRMED'")
    int checkIn(@Param("id") long id, @Param("version") long version, @Param("now") Instant now);

    @Update(
            """
UPDATE booking_order SET status=#{status},charged_slots=#{chargedSlots},terminated_at=#{terminatedAt},
    termination_reason=#{terminationReason},reason_text=#{reasonText},version=version+1
WHERE id=#{id} AND version=#{version}
""")
    int transition(BookingOrder order);

    @Update(
            """
UPDATE slot_occupation SET state='HANDOFF',booking_id=NULL,release_job_id=#{jobId}
WHERE resource_id=#{order.resourceId} AND book_date=#{order.bookDate} AND slot_index>=#{first}
    AND slot_index<#{order.endSlot} AND state='BOOKED' AND booking_id=#{order.id}
""")
    int handoff(
            @Param("order") BookingOrder order,
            @Param("first") int first,
            @Param("jobId") long jobId);

    @Delete(
            """
DELETE FROM user_slot_occupation WHERE user_id=#{order.userId} AND book_date=#{order.bookDate}
    AND slot_index>=#{first} AND slot_index<#{order.endSlot} AND booking_id=#{order.id}
""")
    int releaseUser(@Param("order") BookingOrder order, @Param("first") int first);

    /** 调用方已持有日历锁；候补准入和取消也按该锁串行，不会漏掉并发入队。 */
    @Select("""
        SELECT EXISTS(SELECT 1 FROM waitlist_record
          WHERE resource_id=#{order.resourceId} AND book_date=#{order.bookDate}
            AND status='WAITING' AND expire_at>#{now} AND end_slot=#{order.endSlot}
            AND start_slot BETWEEN #{first} AND #{order.endSlot}-1)
        """)
    boolean hasReleaseCandidate(@Param("order") BookingOrder order,@Param("first") int first,@Param("now") Instant now);

    @Delete("""
        DELETE FROM slot_occupation WHERE resource_id=#{order.resourceId} AND book_date=#{order.bookDate}
          AND slot_index>=#{first} AND slot_index<#{order.endSlot} AND state='BOOKED' AND booking_id=#{order.id}
        """)
    int releaseResource(@Param("order") BookingOrder order,@Param("first") int first);

    @Update(
            "UPDATE app_user SET credit_score=#{score},restriction_until=#{restriction} WHERE"
                    + " id=#{id}")
    int penalize(
            @Param("id") long userId,
            @Param("score") int score,
            @Param("restriction") Instant restriction);
}
