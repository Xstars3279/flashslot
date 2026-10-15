package com.flashslot.operations.service;

import com.flashslot.operations.dto.RecoveryRequest;
import com.flashslot.operations.vo.*;

public interface MessageRecoveryService {
    RecoveryPage<FailedMessageView> list(String cursor, int limit);

    RecoveryActionView retry(String eventId, String key, RecoveryRequest request);
}
