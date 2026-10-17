package com.flashslot.waitlist.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.messaging.service.BusinessEventService;
import com.flashslot.resource.mapper.*;
import com.flashslot.waitlist.mapper.WaitlistMapper;
import com.flashslot.waitlist.service.WaitlistExpiryService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import com.flashslot.common.model.DueItem;

@Service
@Slf4j
public class WaitlistExpiryServiceImpl implements WaitlistExpiryService {
    @Autowired private WaitlistMapper mapper;
    @Autowired private ResourceMapper resources;
    @Autowired private ResourceDayMapper days;
    @Autowired private BusinessEventService events;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;
    @Autowired @Lazy private WaitlistExpiryService self;
    private Instant scanUntil;
    private DueItem cursor;

    /** 独立事务加跨批游标；扫完固定截止时间内的记录后，再从头重试失败项。 */
    @Override
    public synchronized void scanExpired() {
        if(scanUntil==null) scanUntil=clock.instant();
        var batch=mapper.findExpired(scanUntil,cursor==null?null:cursor.dueAt(),cursor==null?0:cursor.id());
        for (DueItem item : batch) {
            cursor=item;
            long id=item.id();
            try {
                self.expireOne(id);
            } catch (RuntimeException ex) {
                log.warn("候补过期处理失败，id={}，exception={}", id, ex.getClass().getSimpleName());
            }
        }
        if(batch.size()<100) { cursor=null; scanUntil=null; }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void expireOne(long id) {
        var located = mapper.selectById(id);
        if (located == null) return;
        resources.lockShared(located.getResourceId());
        days.lockDay(located.getResourceId(), located.getBookDate());
        var candidate = mapper.lock(id);
        var now = clock.instant();
        if (!"WAITING".equals(candidate.getStatus()) || now.isBefore(candidate.getExpireAt()))
            return;
        if (mapper.finish(id, "EXPIRED", "CUTOFF") != 1)
            throw new IllegalStateException("候补过期更新失败");
        events.record(
                "WAITLIST_EXPIRED",
                candidate.getWaitlistNo(),
                candidate.getUserId(),
                json.createObjectNode()
                        .put("waitlistNo", candidate.getWaitlistNo())
                        .put("status", "EXPIRED")
                        .put("reason", "CUTOFF"),
                now);
    }
}
