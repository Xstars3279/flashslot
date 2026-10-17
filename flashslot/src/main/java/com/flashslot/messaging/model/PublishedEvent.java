package com.flashslot.messaging.model;

import java.util.*;

/** 发布事件的类型边界；任务指令不进入用户通知消费者。 */
public final class PublishedEvent {
    public static final String RELEASE = "RELEASE_DISPATCHED";
    private static final Set<String> HISTORY=Set.of("SCHEDULE_CHANGED","CREDIT_CHANGED","WAITLIST_CREATED",
        "RELEASE_REQUESTED","RELEASE_COMPLETED","RELEASE_MATCHED");
    /** 无订阅的留痕事件不冒充待投递；未登记类型拒绝写入，避免拼写错误被静默归档。 */
    public static String initialStatus(String type) {
        if(supported(type)) return "NEW";
        if(HISTORY.contains(type)) return "ARCHIVED";
        throw new IllegalArgumentException("未登记的事件类型");
    }
    private PublishedEvent() {}
    public static boolean supported(String type) {
        return RELEASE.equals(type) || NotificationEventType.supported(type);
    }
    public static List<String> names() {
        var result=new ArrayList<>(NotificationEventType.names());
        result.add(RELEASE);
        return List.copyOf(result);
    }
    public static String routingKey(String type) {
        return RELEASE.equals(type) ? "release.dispatch" : NotificationEventType.valueOf(type).getRoutingKey();
    }
    public static void validate(NotificationEvent event) {
        if(!RELEASE.equals(event.eventType())) { event.validate(); return; }
        var payload=event.payload();
        if(event.eventId()==null || !UUID.fromString(event.eventId()).toString().equals(event.eventId())
            || event.occurredAt()==null || payload==null || !payload.isObject()
            || !payload.path("schemaVersion").isIntegralNumber() || !payload.path("schemaVersion").canConvertToInt()
            || payload.path("schemaVersion").asInt()!=1
            || !payload.path("jobId").isIntegralNumber() || !payload.path("jobId").canConvertToLong()
            || payload.path("jobId").asLong()<=0
            || !Long.toString(payload.path("jobId").asLong()).equals(event.aggregateId()))
            throw new IllegalArgumentException("不支持的释放派发消息结构");
    }
}
