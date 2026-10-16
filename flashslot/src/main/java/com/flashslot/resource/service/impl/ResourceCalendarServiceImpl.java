package com.flashslot.resource.service.impl;

import com.flashslot.resource.entity.ResourceDay;
import com.flashslot.resource.mapper.ResourceMapper;
import com.flashslot.resource.mapper.ResourceDayMapper;
import com.flashslot.resource.service.ResourceCalendarService;
import java.time.*;
import java.util.ArrayList;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResourceCalendarServiceImpl implements ResourceCalendarService {
    private final ResourceMapper resources;
    private final ResourceDayMapper days;
    private final Clock clock;

    @Override
    @Transactional
    public void ensureWindow(long resourceId) {
        var resource = resources.lockExclusive(resourceId);
        if (resource == null || "RETIRED".equals(resource.getStatus())) return;
        // 锁后取时间，避免跨午夜等待时按旧日期补齐。
        LocalDate today = clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var existing = new HashSet<>(days.findDates(resourceId, today, today.plusDays(13)));
        var missing = new ArrayList<ResourceDay>();
        for (int i = 0; i < 14; i++) {
            LocalDate date = today.plusDays(i);
            if (existing.contains(date)) continue;
            var day = new ResourceDay();
            day.setResourceId(resourceId);
            day.setBookDate(date);
            day.setOpenStartSlot(resource.getDefaultOpenStartSlot());
            day.setOpenEndSlot(resource.getDefaultOpenEndSlot());
            missing.add(day);
        }
        if (!missing.isEmpty()) days.insertDays(missing);
    }
}
