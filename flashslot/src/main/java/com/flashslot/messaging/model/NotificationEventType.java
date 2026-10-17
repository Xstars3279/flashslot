package com.flashslot.messaging.model;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/** 站内通知事件；撮合指令由 PublishedEvent 独立路由到释放队列。 */
@Getter
public enum NotificationEventType {
    BOOKING_CREATED("booking.created", "预约成功", "预约已成功，请按时签到。"),
    BOOKING_CANCELED("booking.canceled", "预约已取消", "预约已取消，释放进度可在订单详情查看。"),
    BOOKING_NO_SHOW("booking.no_show", "预约未签到", "预约已超过签到截止时间，已按规则记为爽约。"),
    BOOKING_CHECKED_IN("booking.checked_in", "签到成功", "预约已签到，请按预约时段使用设备。"),
    BOOKING_COMPLETED("booking.completed", "预约已完成", "本次设备预约已结束。"),
    WAITLIST_MATCHED("waitlist.matched", "候补成功", "候补已转为正式预约，请查看订单并按时签到。"),
    WAITLIST_CANCELED("waitlist.canceled", "候补已取消", "你已取消本次候补申请。"),
    WAITLIST_EXPIRED("waitlist.expired", "候补已过期", "候补已超过转正截止时间。"),
    WAITLIST_REJECTED("waitlist.rejected", "候补未转正", "候补未满足当前转正条件，请查看候补记录。");

    private final String routingKey;
    private final String title;
    private final String content;

    NotificationEventType(String routingKey, String title, String content) {
        this.routingKey = routingKey;
        this.title = title;
        this.content = content;
    }

    public static boolean supported(String type) {
        return Arrays.stream(values()).anyMatch(value -> value.name().equals(type));
    }

    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
