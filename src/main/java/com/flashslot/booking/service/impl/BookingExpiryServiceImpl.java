package com.flashslot.booking.service.impl;

import com.flashslot.booking.mapper.*;
import com.flashslot.booking.service.*;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Slf4j
@Service
public class BookingExpiryServiceImpl implements BookingExpiryService {
    @Autowired private BookingExpiryMapper expiry;
    @Autowired private BookingLifecycleService lifecycle;
    @Autowired private Clock clock;

    /** 单条失败隔离；本轮固定 ID 列表继续执行，下轮从最早未完成订单重新扫描。 */
    @Override
    public int scanDueOrders() {
        int changed = 0;
        for (Long id : expiry.due(clock.instant())) {
            try {
                if (process(id)) changed++;
            } catch (RuntimeException ex) {
                log.error("订单到期处理失败，orderId={}，异常类型={}，下一轮重试", id, ex.getClass().getSimpleName());
            }
        }
        return changed;
    }

    @Override
    public boolean process(long orderId) {
        return lifecycle.expireOrder(orderId);
    }
}
