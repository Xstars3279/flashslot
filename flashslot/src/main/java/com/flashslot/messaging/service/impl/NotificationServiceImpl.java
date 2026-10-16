package com.flashslot.messaging.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.auth.service.AccountValidator;
import com.flashslot.common.context.BaseContext;
import com.flashslot.common.web.BusinessException;
import com.flashslot.messaging.dto.NotificationQueryRequest;
import com.flashslot.messaging.entity.Notification;
import com.flashslot.messaging.mapper.NotificationMapper;
import com.flashslot.messaging.service.NotificationService;
import com.flashslot.messaging.vo.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Service
public class NotificationServiceImpl implements NotificationService {
    @Autowired private NotificationMapper mapper;
    @Autowired private AccountPermissionMapper accounts;
    @Autowired private Clock clock;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationPage list(NotificationQueryRequest request) {
        long userId = BaseContext.getCurrentId();
        AccountValidator.requireFormal(accounts.findCurrentAccountById(userId));
        Notification anchor = null;
        if (request.getCursor() != null) {
            anchor =
                    mapper.selectOne(
                            Wrappers.<Notification>lambdaQuery()
                                    .eq(Notification::getId, request.getCursor())
                                    .eq(Notification::getUserId, userId));
            if (anchor == null)
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "分页游标无效");
        }
        var found = mapper.findOwnPage(userId, request, anchor, request.getLimit() + 1);
        boolean more = found.size() > request.getLimit();
        var items = found.stream().limit(request.getLimit()).map(this::toView).toList();
        long unread =
                mapper.selectCount(
                        Wrappers.<Notification>lambdaQuery()
                                .eq(Notification::getUserId, userId)
                                .isNull(Notification::getReadAt));
        return new NotificationPage(
                items, more ? items.get(items.size() - 1).getId() : null, more, unread);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotificationReadView markRead(long id) {
        long userId = BaseContext.getCurrentId();
        AccountValidator.requireFormal(accounts.lockBookingAccount(userId));
        mapper.markRead(id, userId, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        Notification notification =
                mapper.selectOne(
                        Wrappers.<Notification>lambdaQuery()
                                .eq(Notification::getId, id)
                                .eq(Notification::getUserId, userId));
        if (notification == null)
            throw new BusinessException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "通知不存在");
        return new NotificationReadView(id, notification.getReadAt());
    }

    private NotificationView toView(Notification notification) {
        NotificationView view = new NotificationView();
        view.setId(notification.getId());
        view.setType(notification.getType());
        view.setCreatedAt(notification.getCreatedAt());
        view.setReadAt(notification.getReadAt());
        var payload = notification.getPayload();
        view.setTitle(payload.path("title").asText("消息通知"));
        view.setContent(payload.path("content").asText(""));
        view.setOrderNo(payload.path("orderNo").asText(null));
        view.setWaitlistNo(payload.path("waitlistNo").asText(null));
        return view;
    }
}
