package com.flashslot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.model.*;
import com.flashslot.waitlist.mapper.ReleaseWorkMapper;
import com.flashslot.waitlist.service.ReleaseDispatchService;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseDispatchTest {
    @Test void boundaryPreventsAnEarlyCompensationScanFromMatchingNewCancellations() {
        var work=mock(ReleaseWorkMapper.class);
        var events=mock(com.flashslot.messaging.mapper.OutboxEventMapper.class);
        var clock=Clock.fixed(Instant.parse("2026-10-15T02:01:00Z"),ZoneOffset.UTC);
        var boundary=Instant.parse("2026-10-15T02:00:00Z");
        when(work.findDispatchable(boundary)).thenReturn(List.of());
        assertThat(new ReleaseDispatchService(work,events,new ObjectMapper(),clock).scan()).isZero();
        verify(work).findDispatchable(boundary);
        verifyNoInteractions(events);
    }

    @Test void dispatchEnvelopeCannotBeMistakenForANotificationOrAnotherJob() {
        var json=new ObjectMapper();
        var payload=json.createObjectNode().put("schemaVersion",1).put("jobId",42);
        var event=new NotificationEvent(java.util.UUID.randomUUID().toString(),"RELEASE_DISPATCHED","42",Instant.now(),payload);
        assertThatCode(() -> PublishedEvent.validate(event)).doesNotThrowAnyException();
        assertThatThrownBy(event::validate).isInstanceOf(IllegalArgumentException.class);
        var forged=new NotificationEvent(event.eventId(),event.eventType(),"43",event.occurredAt(),payload);
        assertThatThrownBy(() -> PublishedEvent.validate(forged)).isInstanceOf(IllegalArgumentException.class);
    }
}
