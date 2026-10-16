package com.flashslot.booking.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 连续半小时区间，用户身份由认证上下文提供。 */
@Getter
@Setter
public class CreateBookingRequest {
    @NotNull @Positive private Long resourceId;
    @NotNull private LocalDate bookDate;
    @NotNull @Min(0) @Max(47) private Integer startSlot;
    @NotNull @Min(1) @Max(48) private Integer endSlot;
    @NotNull @Pattern(regexp="EXPERIMENT|STUDY|DEVICE_TEST") private String purpose;

    @AssertTrue(message="单次预约必须为连续 1 至 8 格")
    public boolean isValidRange() {
        return startSlot==null || endSlot==null || (endSlot>startSlot && endSlot-startSlot<=8);
    }
}
