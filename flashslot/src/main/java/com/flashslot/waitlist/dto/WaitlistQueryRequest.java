package com.flashslot.waitlist.dto;

import jakarta.validation.constraints.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WaitlistQueryRequest {
    @Pattern(regexp = "[A-Za-z0-9._:-]{0,64}")
    private String cursor;

    @Min(1)
    @Max(100)
    private int limit = 20;
}
