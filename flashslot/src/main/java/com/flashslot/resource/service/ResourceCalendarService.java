package com.flashslot.resource.service;

public interface ResourceCalendarService {
    /** 为单个未退役设备补齐上海今天起 14 天日历，不改写已有记录。 */
    void ensureWindow(long resourceId);
}
