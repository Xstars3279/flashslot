package com.flashslot.booking.service;

import com.flashslot.booking.vo.BookingDetailView;
import com.flashslot.booking.vo.BookingPage;

/** 订单查询业务接口。 */
public interface BookingQueryService {
    /** 查询本人或有效管理员可见的订单详情。 */
    BookingDetailView get(String orderNo);

    /** 查询当前用户的订单分页。 */
    BookingPage list(String cursor, int limit);
}
