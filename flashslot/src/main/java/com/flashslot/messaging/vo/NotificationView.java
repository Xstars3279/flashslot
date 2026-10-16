package com.flashslot.messaging.vo;

import lombok.Data;

import java.time.Instant;

/** 不返回原始 payload、内部事件 ID 或用户 ID。 */
@Data
public class NotificationView {
    private Long id;
    private String type;
    private String title;
    private String content;
    private String orderNo;
    private String waitlistNo;
    private Instant createdAt;
    private Instant readAt;
}
