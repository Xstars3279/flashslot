package com.flashslot.operations.dto;

import jakarta.validation.constraints.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreditAdjustmentRequest extends RecoveryRequest {
    @NotNull
    @Min(-100)
    @Max(100)
    private Integer delta;
}
