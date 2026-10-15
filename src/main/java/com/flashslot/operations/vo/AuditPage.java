package com.flashslot.operations.vo;

import java.util.List;

public record AuditPage(List<AuditView> items, Long nextCursor, boolean hasMore) {}
