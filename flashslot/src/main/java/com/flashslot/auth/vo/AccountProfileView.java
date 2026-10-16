package com.flashslot.auth.vo;

import java.time.Instant;

/** 个人状态响应，不返回密码、签名密钥或其他账号的数据。 */
public record AccountProfileView(long id, String username, String role, int creditScore,
    Instant restrictionUntil, boolean restricted, Instant serverTime) {}
