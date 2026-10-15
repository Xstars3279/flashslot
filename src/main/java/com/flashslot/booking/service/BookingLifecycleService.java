package com.flashslot.booking.service;

import com.flashslot.auth.entity.AppUser;
import com.flashslot.booking.entity.BookingOrder;

import java.time.Instant;

/** 仅供已在事务内取得资源、日历、订单、用户锁的业务入口调用。 */
public interface BookingLifecycleService {
    /** 取得业务锁并结算单张到期订单。 */
    boolean expireOrder(long orderId);

    boolean normalizeLocked(BookingOrder order, AppUser owner, long revision, Instant now);

    void terminateLocked(
            BookingOrder order,
            AppUser owner,
            long revision,
            Instant now,
            boolean administrator,
            String reason);
}
