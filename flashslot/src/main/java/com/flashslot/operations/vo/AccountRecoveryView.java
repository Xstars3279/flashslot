package com.flashslot.operations.vo;

import java.time.Instant;

public record AccountRecoveryView(long userId, int creditScore, Instant restrictionUntil) {}
