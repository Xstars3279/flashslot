package com.flashslot.booking.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/** 我的订单查询参数；cursor 为上一页返回的订单号，空值表示第一页。 */
@Getter
@Setter
public class BookingQueryRequest {
    @Pattern(regexp="[A-Za-z0-9._:-]{0,64}")
    private String cursor;

    @Min(1) @Max(100)
    private int limit=20;
}
