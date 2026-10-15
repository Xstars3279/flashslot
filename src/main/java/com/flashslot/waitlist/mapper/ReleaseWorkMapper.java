package com.flashslot.waitlist.mapper;

import com.flashslot.booking.entity.SlotOccupation;
import com.flashslot.waitlist.entity.*;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/** 扫描不加业务锁；执行时按资源、日历、任务、候补、用户的统一顺序重新读取。 */
@Mapper
public interface ReleaseWorkMapper {
    @Select(
            "SELECT id FROM release_job WHERE status='PENDING' AND next_run_at<=#{now} ORDER BY"
                + " next_run_at,id LIMIT 50")
    List<Long> findDue(Instant now);

    @Select("SELECT * FROM release_job WHERE id=#{id}")
    ReleaseJob locate(long id);

    @Select("SELECT * FROM release_job WHERE id=#{id} FOR UPDATE")
    ReleaseJob lock(long id);

    /** 同一任务的全部占用都参与归属核对，包括误挂到其他设备或日期的异常行。 */
    @Select("SELECT * FROM slot_occupation WHERE release_job_id=#{id} ORDER BY slot_index")
    List<SlotOccupation> ownedSlots(long id);

    /** 当前完整区间按 ID FIFO；已经开始的原区间候补先按到期状态清理。 */
    @Select(
            """
SELECT * FROM waitlist_record WHERE resource_id=#{job.resourceId} AND book_date=#{job.bookDate}
  AND end_slot=#{job.endSlot} AND status='WAITING'
  AND start_slot BETWEEN #{job.startSlot} AND #{maxStart}
  AND (#{mode}<>'NORMAL' OR start_slot=#{first} OR expire_at<=#{now})
ORDER BY id LIMIT 1 FOR UPDATE
""")
    WaitlistRecord lockCandidate(
            @Param("job") ReleaseJob job,
            @Param("first") int first,
            @Param("maxStart") int maxStart,
            @Param("mode") String mode,
            @Param("now") Instant now);

    @Delete(
            """
DELETE FROM slot_occupation WHERE release_job_id=#{job.id} AND resource_id=#{job.resourceId}
  AND book_date=#{job.bookDate} AND state='HANDOFF' AND slot_index<#{first}
""")
    int trimStarted(@Param("job") ReleaseJob job, @Param("first") int first);

    @Delete(
            """
DELETE FROM slot_occupation WHERE release_job_id=#{job.id} AND resource_id=#{job.resourceId}
  AND book_date=#{job.bookDate} AND state='HANDOFF'
""")
    int releaseRemaining(@Param("job") ReleaseJob job);

    @Update(
            """
UPDATE slot_occupation SET state='BOOKED',booking_id=#{bookingId},release_job_id=NULL
WHERE release_job_id=#{job.id} AND resource_id=#{job.resourceId} AND book_date=#{job.bookDate}
  AND state='HANDOFF' AND slot_index>=#{first} AND slot_index<#{job.endSlot}
""")
    int assign(
            @Param("job") ReleaseJob job,
            @Param("first") int first,
            @Param("bookingId") long bookingId);

    @Update(
            """
UPDATE waitlist_record SET status=#{status},reason=#{reason,jdbcType=VARCHAR},matched_booking_id=#{bookingId,jdbcType=BIGINT}
WHERE id=#{id} AND status='WAITING'
""")
    int transitionCandidate(
            @Param("id") long id,
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("bookingId") Long bookingId);

    /** 条件状态防止失败记录覆盖其他执行器已经提交的终态。 */
    @Update(
            """
UPDATE release_job SET status=#{status},effective_start_slot=#{effectiveStartSlot},attempts=#{attempts},
  next_run_at=#{nextRunAt},last_error=#{lastError,jdbcType=VARCHAR}
WHERE id=#{id} AND status='PENDING'
""")
    int save(ReleaseJob job);
}
