package com.flashslot.booking.service;

import com.flashslot.booking.vo.BookingDetailView;
import com.flashslot.booking.vo.CheckInCodeView;

public interface CheckInService {
    CheckInCodeView issueCode(long resourceId);

    BookingDetailView checkIn(String orderNo, String checkInCode);
}
