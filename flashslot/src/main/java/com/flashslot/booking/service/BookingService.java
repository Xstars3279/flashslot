package com.flashslot.booking.service;

import com.flashslot.booking.dto.CreateBookingRequest;
import com.flashslot.booking.vo.BookingView;

/** 预约写业务接口。 */
public interface BookingService {
    /** 创建预约或回放首次幂等结果。 */
    BookingView create(String key, CreateBookingRequest request);
}
