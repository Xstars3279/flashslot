package com.flashslot.operations.service.impl;

import com.flashslot.auth.service.CurrentAdmin;
import com.flashslot.common.web.BusinessException;
import com.flashslot.operations.dto.AuditQueryRequest;
import com.flashslot.operations.entity.AdminAudit;
import com.flashslot.operations.mapper.AdminAuditMapper;
import com.flashslot.operations.service.AdminAuditQueryService;
import com.flashslot.operations.vo.AuditPage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AdminAuditQueryServiceImpl implements AdminAuditQueryService {
    @Autowired private CurrentAdmin admin;
    @Autowired private AdminAuditMapper mapper;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditPage list(AuditQueryRequest request) {
        admin.requireAdmin();
        if (request.getFrom() != null
                && request.getTo() != null
                && request.getFrom().isAfter(request.getTo()))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "起始时间不能晚于结束时间");
        AdminAudit anchor = null;
        if (request.getCursor() != null) {
            anchor = mapper.selectById(request.getCursor());
            if (anchor == null)
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "分页游标无效");
        }
        var found = mapper.findPage(request, anchor, request.getLimit() + 1);
        boolean more = found.size() > request.getLimit();
        var items = found.stream().limit(request.getLimit()).toList();
        return new AuditPage(items, more ? items.get(items.size() - 1).getId() : null, more);
    }
}
