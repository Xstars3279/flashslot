package com.flashslot.operations.service;

import com.flashslot.operations.dto.RecoveryRequest;
import com.flashslot.operations.vo.*;

public interface ReleaseRecoveryService {
    RecoveryPage<ReleaseRecoveryView> list(Long cursor, int limit);

    RecoveryActionView retry(String jobNo, String key, RecoveryRequest request);

    RecoveryActionView release(String jobNo, String key, RecoveryRequest request);
}
