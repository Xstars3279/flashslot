package com.flashslot.messaging.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.model.NotificationEvent;
import com.flashslot.messaging.service.NotificationConsumerService;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.messaging",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NotificationListener {
    @Autowired private ObjectMapper json;
    @Autowired private NotificationConsumerService service;

    @RabbitListener(
            queues = "flashslot.notification",
            containerFactory = "notificationListenerFactory")
    public void receive(Message message) {
        NotificationEvent event;
        try {
            event = json.readValue(message.getBody(), NotificationEvent.class);
            event.validate();
        } catch (Exception ex) {
            throw new AmqpRejectAndDontRequeueException("通知消息格式或版本不支持");
        }
        service.consume(event);
    }
}
