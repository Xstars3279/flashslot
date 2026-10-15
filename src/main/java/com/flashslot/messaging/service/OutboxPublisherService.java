package com.flashslot.messaging.service;

public interface OutboxPublisherService {
    int scanDueEvents();

    boolean publish(String eventId);
}
