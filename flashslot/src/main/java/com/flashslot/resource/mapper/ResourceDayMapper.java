package com.flashslot.resource.mapper;

import com.flashslot.resource.entity.ResourceDay;
import com.flashslot.resource.dto.UpdateResourceDayRequest;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ResourceDayMapper {
    @Select("SELECT book_date FROM resource_day WHERE resource_id=#{resourceId} AND book_date BETWEEN #{start} AND #{end}")
    List<LocalDate> findDates(@Param("resourceId") long resourceId,
        @Param("start") LocalDate start, @Param("end") LocalDate end);

    /** 预约变更占用时在已持有的日历排他锁内递增排期版本。 */
    @Update("UPDATE resource_day SET revision=revision+1 WHERE resource_id=#{resourceId} AND book_date=#{date}")
    int incrementRevision(@Param("resourceId") long resourceId,@Param("date") LocalDate date);
    /** 新增设备时批量建立日历；联合主键禁止重复生成同设备同日期。 */
    @Insert({"<script>",
        "INSERT INTO resource_day(resource_id,book_date,open_start_slot,open_end_slot) VALUES",
        "<foreach collection='days' item='day' separator=','>",
        "(#{day.resourceId},#{day.bookDate},#{day.openStartSlot},#{day.openEndSlot})",
        "</foreach>", "</script>"})
    int insertDays(@Param("days") List<ResourceDay> days);

    /** 按完整联合主键查询日历，用于只读快照，不使用单主键 CRUD。 */
    @Select("SELECT * FROM resource_day WHERE resource_id=#{resourceId} AND book_date=#{date}")
    ResourceDay findDay(@Param("resourceId") long resourceId, @Param("date") LocalDate date);

    /** 锁定设备某日日历；所有当天预约与管理写入都须遵守同一锁协议。 */
    @Select("SELECT * FROM resource_day WHERE resource_id=#{resourceId} AND book_date=#{date} FOR UPDATE")
    ResourceDay lockDay(@Param("resourceId") long resourceId, @Param("date") LocalDate date);

    /** 锁内按完整业务键和预期 revision 更新，成功只影响一行并递增版本。 */
    @Update("""
        UPDATE resource_day SET enabled=#{request.enabled},open_start_slot=#{request.openStartSlot},
          open_end_slot=#{request.openEndSlot},revision=revision+1
        WHERE resource_id=#{resourceId} AND book_date=#{date} AND revision=#{request.revision}
        """)
    int updateDay(@Param("resourceId") long resourceId, @Param("date") LocalDate date,
        @Param("request") UpdateResourceDayRequest request);
}
