package com.flashslot.booking.task;

import com.flashslot.booking.service.BookingExpiryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@RequiredArgsConstructor
public class BookingExpiryScheduler {
    private final BookingExpiryService service;

    @Scheduled(
            fixedDelayString = "${app.booking-expiry.scan-delay-ms:5000}",
            initialDelayString = "${app.booking-expiry.scan-delay-ms:5000}",
            scheduler = "bookingExpiryTaskScheduler")
    public void scan() {
        try {
            service.scanDueOrders();
        } catch (RuntimeException ex) {
            log.error("订单到期扫描失败，异常类型={}，下一轮重试", ex.getClass().getSimpleName());
        }
    }
}
