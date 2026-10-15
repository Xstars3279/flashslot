package com.flashslot.operations.service;

import com.flashslot.operations.entity.AdminAudit;
import com.flashslot.operations.mapper.AdminAuditMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAuditService {
    private final AdminAuditMapper mapper;

    /** 强制参加业务事务；审计失败时业务必须一同回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long actor, String operation, long resourceId, String reason) {
        recordTarget(actor, operation, Long.toString(resourceId), reason);
    }

    /** 记录带复合业务身份的审计，例如“设备ID:日期”，必须与业务同事务。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTarget(long actor, String operation, String target, String reason) {
        AdminAudit audit = new AdminAudit();
        audit.setActorId(actor);
        audit.setOperation(operation);
        audit.setTargetId(target);
        audit.setReason(reason);
        mapper.insert(audit);
    }
}
