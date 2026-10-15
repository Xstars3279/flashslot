package com.flashslot.resource.service;

import com.flashslot.resource.dto.UpdateResourceDayRequest;
import com.flashslot.resource.vo.ResourceDayView;
import com.flashslot.resource.vo.ScheduleView;
import java.time.LocalDate;

public interface ResourceScheduleService {
    /** 查询某台设备某天的 48 个时间格，并按当前身份隐藏他人订单信息。 */
    ScheduleView getSchedule(long resourceId, LocalDate date);

    /** 按预期 revision 修改已有日历；遇到有效占用时禁止关闭或缩短。 */
    ResourceDayView updateDay(long resourceId, LocalDate date, UpdateResourceDayRequest request);
}
