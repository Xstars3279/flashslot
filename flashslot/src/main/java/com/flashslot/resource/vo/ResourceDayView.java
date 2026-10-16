package com.flashslot.resource.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

/** 管理员修改日历后的结果，不包含其他用户的信息。 */
@Data
@NoArgsConstructor
public class ResourceDayView {
    private Long resourceId;
    private LocalDate bookDate;
    private Boolean enabled;
    private Integer openStartSlot;
    private Integer openEndSlot;
    private Long revision;
}
