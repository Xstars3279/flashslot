package com.flashslot.operations.service;

import com.flashslot.operations.dto.*;
import com.flashslot.operations.vo.AccountRecoveryView;

public interface AccountRecoveryService {
    AccountRecoveryView adjustCredit(long userId, String key, CreditAdjustmentRequest request);

    AccountRecoveryView clearRestriction(long userId, String key, RecoveryRequest request);
}
