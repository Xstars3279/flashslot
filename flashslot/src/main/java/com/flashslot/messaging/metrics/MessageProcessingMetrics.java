package com.flashslot.messaging.metrics;

import io.micrometer.core.instrument.*;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/** Bounded dimensions only: every retry/redelivery is another processing attempt. */
@Component
public class MessageProcessingMetrics {
    public enum PublishResult { published, failed, skipped }

    private final MeterRegistry registry;
    private final Map<PublishResult, Counter> publishes = new java.util.EnumMap<>(PublishResult.class);
    private final Map<PublishResult, Timer> publishTimers = new java.util.EnumMap<>(PublishResult.class);
    private final Map<String, Counter> consumes = new java.util.HashMap<>();
    private final Map<String, Timer> consumeTimers = new java.util.HashMap<>();

    public MessageProcessingMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (var result : PublishResult.values()) {
            publishes.put(result, Counter.builder("flashslot.message.publish.attempts")
                    .description("Outbox publish calls, including unclaimed/skipped calls")
                    .tag("result", result.name()).register(registry));
            publishTimers.put(result, Timer.builder("flashslot.message.publish.duration")
                    .description("Outbox publish attempt duration including claim and broker confirmation")
                    .tag("result", result.name()).register(registry));
        }
        for (String channel : new String[] {"notification", "release"}) {
            for (String result : new String[] {"success", "failed"}) {
                String key = channel + ":" + result;
                consumes.put(key, Counter.builder("flashslot.message.consume.attempts")
                        .description("Consumer processing attempts, including retries and duplicate deliveries")
                        .tags("channel", channel, "result", result).register(registry));
                consumeTimers.put(key, Timer.builder("flashslot.message.consume.duration")
                        .description("Consumer attempt duration; transactional notifications include transaction completion")
                        .tags("channel", channel, "result", result).register(registry));
            }
        }
    }

    public boolean publish(Supplier<PublishResult> action) {
        var sample = Timer.start(registry);
        var result = PublishResult.failed;
        try {
            result = action.get();
            return result == PublishResult.published;
        } finally {
            publishes.get(result).increment();
            sample.stop(publishTimers.get(result));
        }
    }

    /** Successful decoding is counted by the business consumer; rejection is one failed attempt. */
    public <T> T decode(String channel, Supplier<T> action) {
        var sample = Timer.start(registry);
        try {
            return action.get();
        } catch (RuntimeException | Error ex) {
            finishConsumer(channel, "failed", sample);
            throw ex;
        }
    }

    public void consume(String channel, Runnable action) {
        var sample = Timer.start(registry);
        try {
            action.run();
        } catch (RuntimeException | Error ex) {
            finishConsumer(channel, "failed", sample);
            throw ex;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    finishConsumer(channel, status == STATUS_COMMITTED ? "success" : "failed", sample);
                }
            });
        } else finishConsumer(channel, "success", sample);
    }

    private void finishConsumer(String channel, String result, Timer.Sample sample) {
        String key = channel + ":" + result;
        consumes.get(key).increment();
        sample.stop(consumeTimers.get(key));
    }
}
