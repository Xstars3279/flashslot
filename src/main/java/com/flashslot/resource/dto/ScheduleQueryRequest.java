package com.flashslot.resource.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

/** 公开排期查询参数；date 是上海业务日期，例如 2026-10-16。 */
@Data
@NoArgsConstructor
public class ScheduleQueryRequest {
    @NotNull
    @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)
    private LocalDate date;
}
