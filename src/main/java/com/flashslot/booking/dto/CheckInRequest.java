package com.flashslot.booking.dto;

import jakarta.validation.constraints.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CheckInRequest {
    @NotBlank
    @Size(max = 512)
    @Pattern(regexp = "[A-Za-z0-9._-]+")
    private String checkInCode;
}
