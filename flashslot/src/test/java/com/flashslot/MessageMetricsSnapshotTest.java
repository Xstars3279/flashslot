package com.flashslot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.flashslot.messaging.mapper.MessageMetricsMapper;
import com.flashslot.messaging.metrics.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MessageMetricsSnapshotTest {
    @Test void gaugesShareOneSnapshotAndAgeUsesInjectedClock() {
        var calls = new AtomicInteger();
        var mapper = mock(MessageMetricsMapper.class);
        var data = new MessageQueueSnapshot();
        data.setOutboxNew(3); data.setOutboxSending(2); data.setOutboxFailed(1);
        data.setOutboxArchived(7); data.setDeadLetterFailed(4);
        data.setReleasePending(5); data.setReleaseManualReview(6);
        data.setOldestPending(Instant.parse("2026-10-17T00:00:00Z"));
        data.setOldestReleaseDue(Instant.parse("2026-10-17T00:01:00Z"));
        when(mapper.snapshot(anyList())).thenAnswer(call -> { calls.incrementAndGet(); return data; });
        var clock = Clock.fixed(Instant.parse("2026-10-17T00:02:00Z"), ZoneOffset.UTC);
        var registry = new SimpleMeterRegistry();
        new MessageQueueMetrics(mapper, clock, registry);
        assertThat(value(registry, "flashslot.message.outbox.backlog")).isEqualTo(5);
        assertThat(value(registry, "flashslot.message.outbox.oldest.age")).isEqualTo(120);
        assertThat(value(registry, "flashslot.message.release.oldest.age")).isEqualTo(60);
        assertThat(registry.get("flashslot.message.outbox.events").tag("status", "failed").gauge().value()).isEqualTo(1);
        assertThat(registry.get("flashslot.message.outbox.events").tag("status", "archived").gauge().value()).isEqualTo(7);
        assertThat(value(registry, "flashslot.message.dead.letters")).isEqualTo(4);
        assertThat(registry.get("flashslot.message.release.jobs").tag("status", "pending").gauge().value()).isEqualTo(5);
        assertThat(registry.get("flashslot.message.release.jobs").tag("status", "manual_review").gauge().value()).isEqualTo(6);
        assertThat(value(registry, "flashslot.message.snapshot.available")).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
        data.setOldestReleaseDue(Instant.parse("2026-10-17T00:03:00Z"));
        assertThat(value(registry, "flashslot.message.release.oldest.age")).isZero();
    }

    @Test void databaseFailureExpiresCachedSuccessAndNeverReportsZeroBacklog() {
        var mapper = mock(MessageMetricsMapper.class);
        var data = new MessageQueueSnapshot();
        data.setOutboxNew(8);
        when(mapper.snapshot(anyList())).thenReturn(data).thenThrow(new IllegalStateException("offline"));
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-10-17T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        var registry = new SimpleMeterRegistry();
        new MessageQueueMetrics(mapper, clock, registry);
        assertThat(value(registry, "flashslot.message.outbox.backlog")).isEqualTo(8);
        assertThat(value(registry, "flashslot.message.outbox.oldest.age")).isZero();
        assertThat(value(registry, "flashslot.message.release.oldest.age")).isZero();
        when(clock.instant()).thenReturn(now.plusSeconds(11));
        assertThat(value(registry, "flashslot.message.outbox.backlog")).isNaN();
        assertThat(value(registry, "flashslot.message.outbox.oldest.age")).isNaN();
        assertThat(value(registry, "flashslot.message.release.oldest.age")).isNaN();
        assertThat(value(registry, "flashslot.message.snapshot.available")).isZero();
    }

    private double value(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}
