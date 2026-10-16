package com.flashslot.booking.service;

/** 订单到期处理，无需请求身份，不能通过 HTTP 直接触发。 */
public interface BookingExpiryService {
    int scanDueOrders();

    boolean process(long orderId);
}
