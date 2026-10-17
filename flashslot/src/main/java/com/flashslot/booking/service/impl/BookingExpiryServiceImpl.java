package com.flashslot.booking.service.impl;

import com.flashslot.booking.mapper.*;
import com.flashslot.booking.service.*;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import com.flashslot.common.model.DueItem;

@Slf4j
@Service
public class BookingExpiryServiceImpl implements BookingExpiryService {
    @Autowired private BookingExpiryMapper expiry;
    @Autowired private BookingLifecycleService lifecycle;
    @Autowired private Clock clock;
    private Instant scanUntil;
    private DueItem cursor;

    /** 每次最多 100 条；固定一轮的截止时间，游标跨调度推进，扫完再重试失败项。 */
    @Override
    public synchronized int scanDueOrders() {
        if(scanUntil==null) scanUntil=clock.instant();
        var batch=expiry.due(scanUntil,cursor==null?null:cursor.dueAt(),cursor==null?0:cursor.id());
        int changed = 0;
        for (DueItem item : batch) {
            cursor=item;
            long id=item.id();
            try {
                if (process(id)) changed++;
            } catch (RuntimeException ex) {
                log.error("订单到期处理失败，orderId={}，异常类型={}，扫描一轮后重试", id, ex.getClass().getSimpleName());
            }
        }
        if(batch.size()<100) { cursor=null; scanUntil=null; }
        return changed;
    }

    @Override
    public boolean process(long orderId) {
        return lifecycle.expireOrder(orderId);
    }
}
