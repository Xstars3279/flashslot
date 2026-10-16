package com.flashslot.operations.controller;

import com.flashslot.common.web.Result;
import com.flashslot.operations.dto.AuditQueryRequest;
import com.flashslot.operations.service.AdminAuditQueryService;
import com.flashslot.operations.vo.AuditPage;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "操作审计")
@RestController
@RequestMapping("/api/v1/admin/audits")
public class AdminAuditController {
    @Autowired private AdminAuditQueryService service;

    @GetMapping
    public Result<AuditPage> list(@ModelAttribute @Valid AuditQueryRequest request) {
        return Result.success(service.list(request));
    }
}
