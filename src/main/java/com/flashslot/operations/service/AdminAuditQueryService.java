package com.flashslot.operations.service;

import com.flashslot.operations.dto.AuditQueryRequest;
import com.flashslot.operations.vo.AuditPage;

public interface AdminAuditQueryService {
    AuditPage list(AuditQueryRequest request);
}
