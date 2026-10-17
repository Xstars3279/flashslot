package com.flashslot.common.model;

import java.time.Instant;

/** 到期扫描的稳定排序键；时间相同时按 ID 继续，避免漏项。 */
public record DueItem(long id, Instant dueAt) {}
