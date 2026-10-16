package com.flashslot.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashslot.resource.entity.Resource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 基础 CRUD 由 MyBatis-Plus 提供，版本更新由乐观锁拦截器处理。 */
@Mapper
public interface ResourceMapper extends BaseMapper<Resource> {
    /** 业务事务先取得资源共享锁，避免资源配置/退役与当日日历写入交叉执行。 */
    @Select("SELECT * FROM resource WHERE id=#{id} FOR SHARE")
    Resource lockShared(long id);

    /** 日历补齐先锁设备，串行化同设备补齐及默认配置修改。 */
    @Select("SELECT * FROM resource WHERE id=#{id} FOR UPDATE")
    Resource lockExclusive(long id);
}
