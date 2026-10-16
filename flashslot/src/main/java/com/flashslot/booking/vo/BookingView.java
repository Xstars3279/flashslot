package com.flashslot.booking.vo;

import java.time.*;
import lombok.Getter;
import lombok.Setter;

/** 创建成功的不可变业务快照；回放不会查询订单的最新状态。 */
@Getter
@Setter
public class BookingView {
    private String orderNo;
    private Long resourceId;
    private LocalDate bookDate;
    private Integer startSlot;
    private Integer endSlot;
    private String purpose;
    private String status;
    private String source;
    private Integer chargedSlots;
    private Instant startAt;
    private Instant endAt;
    private Instant checkInOpenAt;
    private Instant checkInDeadline;
}
