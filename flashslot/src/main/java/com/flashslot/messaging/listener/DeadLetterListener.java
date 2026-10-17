package com.flashslot.messaging.listener;
import com.flashslot.messaging.service.DeadLetterStore;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(prefix="app.messaging",name="enabled",havingValue="true",matchIfMissing=true)
public class DeadLetterListener {
    private final DeadLetterStore store;
    @RabbitListener(queues={"flashslot.notification.dead","flashslot.release.dead"},containerFactory="deadLetterListenerFactory")
    public void receive(Message message) { store.capture(message.getBody()); }
}
