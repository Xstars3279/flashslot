package com.flashslot.operations.vo;

import java.util.List;

public record RecoveryPage<T>(List<T> items, String nextCursor, boolean hasMore) {}
