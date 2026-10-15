package com.flashslot.messaging.vo;

import java.util.List;

public record NotificationPage(
        List<NotificationView> items, Long nextCursor, boolean hasMore, long unreadCount) {}
