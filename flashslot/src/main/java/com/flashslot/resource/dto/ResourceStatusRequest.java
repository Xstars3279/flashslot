package com.flashslot.resource.dto;
import jakarta.validation.constraints.*;
public record ResourceStatusRequest(@NotNull @PositiveOrZero Long version,
    @NotNull @Pattern(regexp="ACTIVE|PAUSED|RETIRED") String status,
    @NotBlank @Size(max=256) String reason) {}
