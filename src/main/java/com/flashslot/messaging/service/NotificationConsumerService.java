package com.flashslot.messaging.service;

import com.flashslot.messaging.model.NotificationEvent;

public interface NotificationConsumerService {
    void consume(NotificationEvent event);
}
