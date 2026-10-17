package com.flashslot.messaging.service.impl;

import com.flashslot.messaging.entity.OutboxEvent;
import com.flashslot.messaging.mapper.OutboxEventMapper;
import com.flashslot.messaging.model.PublishedEvent;
import com.flashslot.messaging.service.OutboxDeliveryService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

@Service
public class OutboxDeliveryServiceImpl implements OutboxDeliveryService {
    private static final long[] RETRY_SECONDS = {1, 5, 30, 120, 600};
    @Autowired private OutboxEventMapper mapper;
    @Autowired private Clock clock;

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public OutboxEvent claim(String eventId) {
        OutboxEvent event = mapper.lock(eventId);
        if (event == null || !PublishedEvent.supported(event.getEventType())) return null;
        Instant now = clock.instant();
        boolean ready = "NEW".equals(event.getStatus()) && !event.getNextRunAt().isAfter(now);
        boolean expired =
                "SENDING".equals(event.getStatus())
                        && (event.getLeaseUntil() == null || !event.getLeaseUntil().isAfter(now));
        if (!ready && !expired) return null;
        if (event.getAttempts() >= 12) {
            requireOne(mapper.finishFailure(eventId, "FAILED", now, "LEASE_RETRY_EXHAUSTED"));
            return null;
        }
        event.setAttempts(event.getAttempts() + 1);
        event.setStatus("SENDING");
        event.setLeaseToken(UUID.randomUUID().toString());
        event.setLeaseUntil(now.plusSeconds(60));
        requireOne(mapper.claim(event));
        return event;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(String eventId, String leaseToken) {
        return mapper.markPublished(eventId, leaseToken, clock.instant()) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(String eventId, String leaseToken, String reason) {
        OutboxEvent event = mapper.lock(eventId);
        if (event == null
                || !"SENDING".equals(event.getStatus())
                || !Objects.equals(leaseToken, event.getLeaseToken())) return false;
        boolean exhausted = event.getAttempts() >= 12;
        Instant next =
                clock.instant()
                        .plusSeconds(
                                RETRY_SECONDS[
                                        Math.min(
                                                event.getAttempts() - 1,
                                                RETRY_SECONDS.length - 1)]);
        requireOne(mapper.finishFailure(eventId, exhausted ? "FAILED" : "NEW", next, reason));
        return true;
    }

    private void requireOne(int rows) {
        if (rows != 1) throw new IllegalStateException("消息投递状态更新失败");
    }
}
