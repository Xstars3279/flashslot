package com.flashslot.operations.vo;

import java.time.*;

public record ReleaseRecoveryView(
        long id,
        String jobNo,
        long resourceId,
        LocalDate bookDate,
        int effectiveStartSlot,
        int endSlot,
        String status,
        int attempts,
        String lastError) {}
