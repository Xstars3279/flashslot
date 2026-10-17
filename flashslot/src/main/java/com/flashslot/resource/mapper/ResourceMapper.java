package com.flashslot.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashslot.resource.entity.Resource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 基础 CRUD 由 MyBatis-Plus 提供，版本更新由乐观锁拦截器处理。 */
@Mapper
public interface ResourceMapper extends BaseMapper<Resource> {
    /** 当前事务快照中的设备版本；不把版本指针缓存在Redis。 */
    @Select("SELECT version FROM resource WHERE id=#{id}")
    Long findVersion(long id);
    /** 业务事务先取得资源共享锁，避免资源配置/退役与当日日历写入交叉执行。 */
    @Select("SELECT * FROM resource WHERE id=#{id} FOR SHARE")
    Resource lockShared(long id);

    /** 日历补齐先锁设备，串行化同设备补齐及默认配置修改。 */
    @Select("SELECT * FROM resource WHERE id=#{id} FOR UPDATE")
    Resource lockExclusive(long id);

    /** 必须持有资源排他锁；该锁与所有业务资源共享锁互斥。 */
    @Select("""
        SELECT (
          EXISTS(SELECT 1 FROM booking_order WHERE resource_id=#{id} AND status IN ('CONFIRMED','CHECKED_IN') AND end_at>#{now})
          OR EXISTS(SELECT 1 FROM waitlist_record WHERE resource_id=#{id} AND status='WAITING' AND expire_at>#{now})
          OR EXISTS(SELECT 1 FROM slot_occupation WHERE resource_id=#{id} AND state='HANDOFF'
            AND (book_date>#{today} OR (book_date=#{today} AND slot_index>=#{firstSlot})))
        )
        """)
    boolean hasActiveUse(@org.apache.ibatis.annotations.Param("id") long id,
        @org.apache.ibatis.annotations.Param("now") java.time.Instant now,
        @org.apache.ibatis.annotations.Param("today") java.time.LocalDate today,
        @org.apache.ibatis.annotations.Param("firstSlot") int firstSlot);
}
