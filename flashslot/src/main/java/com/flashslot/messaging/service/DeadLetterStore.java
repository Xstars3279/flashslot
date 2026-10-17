package com.flashslot.messaging.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.entity.DeadLetter;
import com.flashslot.messaging.mapper.DeadLetterMapper;
import com.flashslot.messaging.model.NotificationEvent;
import com.flashslot.messaging.model.PublishedEvent;
import java.time.Clock;
import java.security.*;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class DeadLetterStore {
    private final DeadLetterMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;

    /** 事务提交成功后监听容器才 ACK；同一原始消息重复接收只累计次数。 */
    @Transactional
    public void capture(byte[] body) {
        var letter=new DeadLetter();
        letter.setBody(body); letter.setReceivedAt(clock.instant());
        try { letter.setMessageHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))); }
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
        mapper.capture(letter);
    }
}
