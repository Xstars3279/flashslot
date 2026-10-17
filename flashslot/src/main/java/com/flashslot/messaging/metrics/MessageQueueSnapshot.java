package com.flashslot.messaging.metrics;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Aggregate columns only; payloads and message bodies never enter metrics collection. */
@Getter @Setter
public class MessageQueueSnapshot {
    private long outboxNew;
    private long outboxSending;
    private long outboxFailed;
    private long outboxArchived;
    private Instant oldestPending;
    private long deadLetterFailed;
    private long releasePending;
    private Instant oldestReleaseDue;
    private long releaseManualReview;
}
