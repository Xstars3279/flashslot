package com.flashslot.booking.dto;

import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

/** 用户取消可不填原因；管理员取消他人订单时原因必填。 */
@Getter
@Setter
public class CancelBookingRequest {
    @Size(max = 256)
    private String reason;
}
