package com.flashslot.waitlist.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.*;

/** 候补公开投影，不包含用户身份或数据库内部关联 ID。 */
@Getter
@Setter
public class WaitlistView {
    private String waitlistNo;
    private Long resourceId;
    private String resourceName;
    private String resourceLocation;
    private LocalDate bookDate;
    private Integer startSlot;
    private Integer endSlot;
    private String purpose;
    private String status;
    private String reason;
    private Instant expireAt;
    private Instant createdAt;
    private Instant updatedAt;
    private String matchedOrderNo;

    /** 同设备同日同完整区间的近似 FIFO 位置，非有效 WAITING 时为 null。 */
    private Long queuePosition;
}
