package com.flashslot.booking.vo;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 查询当前订单状态；创建接口的幂等快照仍使用 BookingView。 */
@Getter
@Setter
public class BookingDetailView extends BookingView {
    private String resourceName;
    private String resourceLocation;
    private Instant checkedInAt;
    private Instant terminatedAt;
    private String terminationReason;
    private String reasonText;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
    /** 本订单取消后触发的释放任务，而不是候补转正的来源任务。 */
    private String releaseJobNo;
    /** 无释放任务时为 null；其余为 PENDING/MATCHED/RELEASED/MANUAL_REVIEW。 */
    private String releaseStatus;
}
