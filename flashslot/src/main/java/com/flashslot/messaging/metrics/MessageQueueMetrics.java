package com.flashslot.messaging.metrics;

import com.flashslot.messaging.mapper.MessageMetricsMapper;
import com.flashslot.messaging.model.PublishedEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.function.ToDoubleFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Lazy shared cache: all gauges use at most one aggregate query per ten seconds. */
@Component
@Slf4j
public class MessageQueueMetrics {
    private static final Duration CACHE_TTL = Duration.ofSeconds(10);
    private final MessageMetricsMapper mapper;
    private final Clock clock;
    private volatile Cached cached;

    public MessageQueueMetrics(MessageMetricsMapper mapper, Clock clock, MeterRegistry registry) {
        this.mapper = mapper;
        this.clock = clock;
        gauge(registry, "flashslot.message.outbox.backlog", s -> s.getOutboxNew() + s.getOutboxSending());
        Gauge.builder("flashslot.message.outbox.oldest.age", this, self -> self.oldestAge())
                .description("Age of oldest publishable NEW/SENDING event; zero when empty; NaN when database unavailable")
                .baseUnit("seconds").register(registry);
        statusGauge(registry, "flashslot.message.outbox.events", "new", MessageQueueSnapshot::getOutboxNew);
        statusGauge(registry, "flashslot.message.outbox.events", "sending", MessageQueueSnapshot::getOutboxSending);
        statusGauge(registry, "flashslot.message.outbox.events", "failed", MessageQueueSnapshot::getOutboxFailed);
        statusGauge(registry, "flashslot.message.outbox.events", "archived", MessageQueueSnapshot::getOutboxArchived);
        gauge(registry, "flashslot.message.dead.letters", MessageQueueSnapshot::getDeadLetterFailed);
        statusGauge(registry, "flashslot.message.release.jobs", "pending", MessageQueueSnapshot::getReleasePending);
        statusGauge(registry, "flashslot.message.release.jobs", "manual_review", MessageQueueSnapshot::getReleaseManualReview);
        Gauge.builder("flashslot.message.release.oldest.age", this, self -> self.oldestReleaseAge())
                .description("Overdue age of the oldest PENDING release job next_run_at; zero when empty or not yet due; NaN when database unavailable")
                .baseUnit("seconds").register(registry);
        Gauge.builder("flashslot.message.snapshot.available", this, self -> self.snapshot() == null ? 0 : 1)
                .description("One when the cached database metrics snapshot is available, zero when collection failed")
                .register(registry);
    }

    private void gauge(MeterRegistry registry, String name, ToDoubleFunction<MessageQueueSnapshot> value) {
        Gauge.builder(name, this, self -> self.value(value)).register(registry);
    }

    private void statusGauge(MeterRegistry registry, String name, String status, ToDoubleFunction<MessageQueueSnapshot> value) {
        Gauge.builder(name, this, self -> self.value(value)).tag("status", status).register(registry);
    }

    private double value(ToDoubleFunction<MessageQueueSnapshot> value) {
        var data = snapshot();
        return data == null ? Double.NaN : value.applyAsDouble(data);
    }

    private double oldestAge() {
        var data = snapshot();
        if (data == null) return Double.NaN;
        if (data.getOldestPending() == null) return 0;
        return Math.max(0, Duration.between(data.getOldestPending(), clock.instant()).toMillis() / 1000.0);
    }

    private double oldestReleaseAge() {
        var data = snapshot();
        if (data == null) return Double.NaN;
        if (data.getOldestReleaseDue() == null) return 0;
        return Math.max(0, Duration.between(data.getOldestReleaseDue(), clock.instant()).toMillis() / 1000.0);
    }

    private MessageQueueSnapshot snapshot() {
        var current = cached;
        var now = clock.instant();
        if (current != null && now.isBefore(current.expiresAt())) return current.data();
        return refresh();
    }

    private synchronized MessageQueueSnapshot refresh() {
        var now = clock.instant();
        var current = cached;
        if (current != null && now.isBefore(current.expiresAt())) return current.data();
        MessageQueueSnapshot data = null;
        try {
            data = mapper.snapshot(PublishedEvent.names());
        } catch (RuntimeException ex) {
            log.warn("消息指标数据库快照读取失败，异常类型={}", ex.getClass().getSimpleName());
        }
        cached = new Cached(clock.instant().plus(CACHE_TTL), data);
        return data;
    }

    private record Cached(Instant expiresAt, MessageQueueSnapshot data) {}
}
