package com.flashslot.resource.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

/** 单个半小时时间格；orderNo 仅对当前登录的订单所有者返回。 */
@Data
@NoArgsConstructor
public class ScheduleSlotView {
    private Integer slotIndex;
    private Instant startAt;
    private Instant endAt;
    private String state;
    private boolean mine;
    private String orderNo;
    private boolean canBook;
    private boolean canWaitlist;
}
