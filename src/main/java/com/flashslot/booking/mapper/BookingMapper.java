package com.flashslot.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashslot.booking.entity.BookingOrder;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 订单单主键使用框架插入，联合主键占用使用普通批量 INSERT。 */
@Mapper
public interface BookingMapper extends BaseMapper<BookingOrder> {
    /** 持有日历锁后检查设备目标区间；HANDOFF 同样阻止直接预约。 */
    @Select("SELECT COUNT(*) FROM slot_occupation WHERE resource_id=#{resourceId} AND book_date=#{date} AND slot_index>=#{start} AND slot_index<#{end}")
    int resourceConflicts(@Param("resourceId") long resourceId,@Param("date") LocalDate date,@Param("start") int start,@Param("end") int end);

    /** 持有用户锁后检查跨设备的重叠区间。 */
    @Select("SELECT COUNT(*) FROM user_slot_occupation WHERE user_id=#{userId} AND book_date=#{date} AND slot_index>=#{start} AND slot_index<#{end}")
    int userConflicts(@Param("userId") long userId,@Param("date") LocalDate date,@Param("start") int start,@Param("end") int end);

    /** 唯一键是最终防线，任何一格冲突均抛异常并回滚整个预约事务。 */
    @Insert({"<script>","INSERT INTO slot_occupation(resource_id,book_date,slot_index,state,booking_id) VALUES",
        "<foreach collection='slots' item='slot' separator=','>(#{order.resourceId},#{order.bookDate},#{slot},'BOOKED',#{order.id})</foreach>","</script>"})
    int occupyResource(@Param("order") BookingOrder order,@Param("slots") List<Integer> slots);

    /** 与设备占用、订单在同一事务内写全，禁止 INSERT IGNORE。 */
    @Insert({"<script>","INSERT INTO user_slot_occupation(user_id,book_date,slot_index,booking_id) VALUES",
        "<foreach collection='slots' item='slot' separator=','>(#{order.userId},#{order.bookDate},#{slot},#{order.id})</foreach>","</script>"})
    int occupyUser(@Param("order") BookingOrder order,@Param("slots") List<Integer> slots);

    /** 同区间本人候补先于用户锁锁定，保留将来候补转正的统一锁序。 */
    @Select("""
        SELECT id FROM waitlist_record WHERE resource_id=#{resourceId} AND book_date=#{date}
          AND user_id=#{userId} AND start_slot=#{start} AND end_slot=#{end} AND status='WAITING' ORDER BY id FOR UPDATE
        """)
    List<Long> lockOwnWaitlist(@Param("resourceId") long resourceId,@Param("date") LocalDate date,
        @Param("userId") long userId,@Param("start") int start,@Param("end") int end);

    /** 成功直接预约后撤销同区间待处理候补，避免之后重复转正。 */
    @Update("UPDATE waitlist_record SET status='CANCELED',reason='DIRECT_BOOKING' WHERE id=#{id} AND status='WAITING'")
    int cancelWaitlist(long id);
}
