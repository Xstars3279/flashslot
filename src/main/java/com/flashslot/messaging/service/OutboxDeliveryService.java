package com.flashslot.messaging.service;

import com.flashslot.messaging.entity.OutboxEvent;

/** 只管理事件领取和投递状态，网络发送不放在数据库事务内。 */
public interface OutboxDeliveryService {
    OutboxEvent claim(String eventId);

    boolean markPublished(String eventId, String leaseToken);

    boolean recordFailure(String eventId, String leaseToken, String reason);
}
