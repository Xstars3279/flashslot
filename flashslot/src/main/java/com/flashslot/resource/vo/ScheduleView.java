package com.flashslot.resource.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 单日完整排期；时间使用 UTC，日期使用上海业务日期。 */
@Data
@NoArgsConstructor
public class ScheduleView {
    private Long resourceId;
    private LocalDate bookDate;
    private Long revision;
    private String resourceStatus;
    private Boolean enabled;
    private Integer openStartSlot;
    private Integer openEndSlot;
    private Instant serverTime;
    private List<ScheduleSlotView> slots;
}
