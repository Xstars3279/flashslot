package com.flashslot.messaging.vo;

import java.time.Instant;

public record NotificationReadView(long id, Instant readAt) {}
