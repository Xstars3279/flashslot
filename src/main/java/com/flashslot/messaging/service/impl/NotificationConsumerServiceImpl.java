package com.flashslot.messaging.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.auth.mapper.AccountPermissionMapper;
import com.flashslot.messaging.entity.Notification;
import com.flashslot.messaging.mapper.*;
import com.flashslot.messaging.model.*;
import com.flashslot.messaging.service.NotificationConsumerService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationConsumerServiceImpl implements NotificationConsumerService {
    @Autowired private AccountPermissionMapper accounts;
    @Autowired private NotificationMapper notifications;
    @Autowired private ConsumerInboxMapper inbox;
    @Autowired private ObjectMapper json;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void consume(NotificationEvent event) {
        event.validate();
        long userId = event.payload().get("userId").asLong();
        // 与业务、已读写入口保持用户锁在前；停用账号的历史消息仍正常保存。
        if (accounts.lockBookingAccount(userId) == null)
            throw new IllegalArgumentException("通知用户不存在");
        if (inbox.completed(event.eventId()) > 0) return;
        var type = NotificationEventType.valueOf(event.eventType());
        var data = event.data();
        // 候补转正另有 WAITLIST_MATCHED 通知，不再重复发一条预约成功。
        boolean silent =
                type == NotificationEventType.BOOKING_CREATED
                        && "WAITLIST".equals(data.path("source").asText());
        if (!silent
                && notifications.selectCount(
                                Wrappers.<Notification>lambdaQuery()
                                        .eq(Notification::getUserId, userId)
                                        .eq(Notification::getEventId, event.eventId())
                                        .eq(Notification::getType, event.eventType()))
                        == 0) {
            var payload =
                    json.createObjectNode()
                            .put("schemaVersion", 1)
                            .put("title", type.getTitle())
                            .put("content", type.getContent());
            if (data.path("orderNo").isTextual())
                payload.put("orderNo", data.get("orderNo").asText());
            if (data.path("waitlistNo").isTextual())
                payload.put("waitlistNo", data.get("waitlistNo").asText());
            if (event.eventType().startsWith("BOOKING_") && !payload.has("orderNo"))
                payload.put("orderNo", event.aggregateId());
            Notification notification = new Notification();
            notification.setUserId(userId);
            notification.setEventId(event.eventId());
            notification.setType(event.eventType());
            notification.setPayload(payload);
            if (notifications.insert(notification) != 1) throw new IllegalStateException("通知保存失败");
        }
        // 通知和完成记录一起提交，监听方法返回后容器才 ACK。
        if (inbox.complete(event.eventId()) != 1) throw new IllegalStateException("消息消费结果保存失败");
    }
}
