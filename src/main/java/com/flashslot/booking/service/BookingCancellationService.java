package com.flashslot.booking.service;

import com.flashslot.booking.dto.CancelBookingRequest;
import com.flashslot.booking.vo.BookingDetailView;

public interface BookingCancellationService {
    /** 本人取消或管理员取消，过期状态在业务锁内先归一化。 */
    BookingDetailView cancel(String orderNo, String key, CancelBookingRequest request);
}
