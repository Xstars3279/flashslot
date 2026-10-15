package com.flashslot.booking.vo;

import java.time.Instant;

/** 管理员展示的设备短时签到凭据；不能当作登录 Token。 */
public record CheckInCodeView(long resourceId, String checkInCode, Instant expiresAt) {}
