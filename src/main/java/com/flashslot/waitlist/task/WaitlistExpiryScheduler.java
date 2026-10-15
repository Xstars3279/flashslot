package com.flashslot.waitlist.task;

import com.flashslot.waitlist.service.WaitlistExpiryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        name = "app.release.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WaitlistExpiryScheduler {
    private final WaitlistExpiryService service;

    @Scheduled(
            fixedDelayString = "${app.waitlist-expiry.scan-delay-ms:5000}",
            scheduler = "releaseTaskScheduler")
    public void scan() {
        try {
            service.scanExpired();
        } catch (RuntimeException ex) {
            log.warn("候补过期扫描失败，exception={}", ex.getClass().getSimpleName());
        }
    }
}
