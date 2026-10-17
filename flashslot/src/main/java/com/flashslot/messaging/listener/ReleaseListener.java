package com.flashslot.messaging.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.model.*;
import com.flashslot.messaging.service.ReleaseConsumerService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(prefix="app.messaging",name="enabled",havingValue="true",matchIfMissing=true)
public class ReleaseListener {
    private final ObjectMapper json;
    private final ReleaseConsumerService service;
    @RabbitListener(queues="flashslot.release",containerFactory="releaseListenerFactory")
    public void receive(Message message) {
        NotificationEvent event;
        try {
            event=json.readValue(message.getBody(),NotificationEvent.class);
            PublishedEvent.validate(event);
            if(!PublishedEvent.RELEASE.equals(event.eventType())) throw new IllegalArgumentException();
        } catch(Exception ex) { throw new AmqpRejectAndDontRequeueException("撮合消息格式或版本不支持"); }
        service.consume(event);
    }
}
