package com.flashslot.messaging.controller;

import com.flashslot.common.web.Result;
import com.flashslot.messaging.dto.NotificationQueryRequest;
import com.flashslot.messaging.service.NotificationService;
import com.flashslot.messaging.vo.*;

import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "站内通知")
@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {
    @Autowired private NotificationService service;

    @GetMapping
    public Result<NotificationPage> list(@ModelAttribute @Valid NotificationQueryRequest request) {
        return Result.success(service.list(request));
    }

    @PostMapping("/{id}/read")
    public Result<NotificationReadView> read(@PathVariable @Positive long id) {
        return Result.success(service.markRead(id));
    }
}
