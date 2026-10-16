package com.flashslot.operations.dto;

import jakarta.validation.constraints.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RecoveryRequest {
    @NotBlank
    @Size(max = 255)
    private String reason;
}
