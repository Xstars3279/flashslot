package com.flashslot.messaging.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.entity.DeadLetter;
import com.flashslot.messaging.mapper.DeadLetterMapper;
import com.flashslot.messaging.model.NotificationEvent;
import com.flashslot.messaging.model.PublishedEvent;
import java.time.Clock;
import java.security.*;
import java.util.HexFormat;
import java.util.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class DeadLetterStore {
    private final DeadLetterMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;

    /** 兼容旧恢复调用，缺少来源时明确记 UNKNOWN。 */
    @Transactional
    public void capture(byte[] body) {
        capture(new Message(body,new MessageProperties()),null);
    }

    /** 事务提交后才 ACK；按消费来源+原文去重，失败原因只用受控分类，不保存异常文本。 */
    @Transactional
    public void capture(Message message,Throwable failure) {
        byte[] body=message.getBody();
        var letter=new DeadLetter();
        var properties=message.getMessageProperties();
        String queue=queue(properties.getConsumerQueue());
        String exchange=bounded(properties.getReceivedExchange()),routing=bounded(properties.getReceivedRoutingKey());
        long deaths=0;
        Object header=properties.getHeaders().get("x-death");
        if(header instanceof List<?> entries) {
            for(Object entry:entries.stream().limit(16).toList()) {
                if(!(entry instanceof Map<?,?> death)) continue;
                if(death.get("count") instanceof Number count) deaths=Math.min(1_000_000_000L,deaths+Math.max(0,Math.min(1_000_000_000L,count.longValue())));
                if(queue.endsWith(".dead") || "UNKNOWN".equals(queue)) {
                    String origin=queue(death.get("queue") instanceof String s?s:null);
                    if(!"UNKNOWN".equals(origin) && !origin.endsWith(".dead")) {
                        queue=origin;
                        exchange=bounded(death.get("exchange") instanceof String s?s:null);
                        Object keys=death.get("routing-keys");
                        if(keys instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof String s) routing=bounded(s);
                    }
                }
            }
        }
        letter.setBody(body); letter.setReceivedAt(clock.instant()); letter.setLastReceivedAt(letter.getReceivedAt());
        letter.setSourceQueue(queue); letter.setOriginalExchange(exchange); letter.setRoutingKey(routing); letter.setBrokerDeathCount(deaths);
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            // UNKNOWN沿用旧原文哈希，已知来源按队列隔离，避免不同消费者失败被合并。
            if(!"UNKNOWN".equals(queue)) { digest.update(queue.getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte)0); }
            letter.setMessageHash(HexFormat.of().formatHex(digest.digest(body)));
        }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        letter.setEventType("UNKNOWN"); letter.setErrorCode("FORMAT_UNSUPPORTED");
        try {
            var root=json.readTree(body);
            String id=root.path("eventId").asText("");
            if(id.matches("[a-f0-9-]{36}")) letter.setEventId(id);
            String type=root.path("eventType").asText("");
            if(PublishedEvent.supported(type)) letter.setEventType(type);
            PublishedEvent.validate(json.treeToValue(root,NotificationEvent.class));
            letter.setErrorCode("CONSUMPTION_FAILED");
        } catch(Exception ex) { /* 保存原始消息，列表仅展示受控错误码。 */ }
        String category="FORMAT_UNSUPPORTED".equals(letter.getErrorCode())?"FORMAT_UNSUPPORTED":category(failure,deaths);
        letter.setFailureCategory(category); letter.setFailureReason(reason(category));
        mapper.capture(letter);
    }

    private String queue(String value) {
        return Set.of("flashslot.notification","flashslot.release","flashslot.notification.dead","flashslot.release.dead").contains(value==null?"":value)?value:"UNKNOWN";
    }
    private String bounded(String value) {
        return value==null?null:value.replaceAll("[\\p{Cntrl}]","").substring(0,Math.min(128,value.replaceAll("[\\p{Cntrl}]","").length()));
    }
    private String category(Throwable failure,long deaths) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable current=failure;current!=null && seen.size()<16 && seen.add(current);current=current.getCause()) {
            if(current instanceof org.springframework.dao.PessimisticLockingFailureException) return "LOCK_CONFLICT";
            if(current instanceof org.springframework.dao.DataIntegrityViolationException) return "DATABASE_CONSTRAINT";
            if(current instanceof org.springframework.dao.DataAccessResourceFailureException
                || current instanceof org.springframework.dao.RecoverableDataAccessException) return "DATABASE_UNAVAILABLE";
            if(current instanceof org.springframework.dao.DataAccessException) return "DATABASE_ERROR";
            if(current instanceof com.flashslot.common.web.BusinessException) return "BUSINESS_REJECTED";
        }
        return failure!=null?"PROCESSING_FAILED":deaths>0?"BROKER_REJECTED":"UNKNOWN";
    }
    private String reason(String category) {
        return switch(category) {
            case "FORMAT_UNSUPPORTED" -> "消息格式或版本不支持";
            case "LOCK_CONFLICT" -> "数据库锁冲突，核查并发后重试";
            case "DATABASE_CONSTRAINT" -> "数据库约束冲突，核查业务数据";
            case "DATABASE_UNAVAILABLE" -> "数据库暂不可用，恢复依赖后重试";
            case "DATABASE_ERROR" -> "数据库处理失败，核查依赖和数据";
            case "BUSINESS_REJECTED" -> "业务处理拒绝，核查当前业务状态";
            case "PROCESSING_FAILED" -> "消费处理失败，核查应用日志";
            case "BROKER_REJECTED" -> "Broker 转入死信，原应用异常不可得";
            default -> "缺少失败上下文，需核查来源";
        };
    }
}
