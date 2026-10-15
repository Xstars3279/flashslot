package com.flashslot.booking.service;

import com.flashslot.booking.vo.CheckInCodeView;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

import javax.crypto.*;

/** 设备专用 HMAC 凭据，与登录 JWT 使用不同格式和签名域，不能互相替代。 */
@Component
@RequiredArgsConstructor
public class CheckInCodeSigner {
    private final SecretKey jwtSigningKey;

    public CheckInCodeView issue(long resourceId, Instant now) {
        long issued = now.toEpochMilli(), expires = issued + 60_000;
        String body = "v1." + resourceId + "." + issued + "." + expires + "." + UUID.randomUUID();
        String token =
                body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));
        return new CheckInCodeView(resourceId, token, Instant.ofEpochMilli(expires));
    }

    public boolean valid(String token, long resourceId, Instant now) {
        if (token == null || token.length() > 512) return false;
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 6 || !"v1".equals(parts[0])) return false;
            String body = token.substring(0, token.lastIndexOf('.'));
            if (!MessageDigest.isEqual(sign(body), Base64.getUrlDecoder().decode(parts[5])))
                return false;
            long issued = Long.parseLong(parts[2]), expires = Long.parseLong(parts[3]);
            return Long.parseLong(parts[1]) == resourceId
                    && expires > issued
                    && expires - issued == 60_000
                    && !now.isBefore(Instant.ofEpochMilli(issued))
                    && now.isBefore(Instant.ofEpochMilli(expires));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(jwtSigningKey);
            return mac.doFinal(("flashslot:check-in:v1\n" + body).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("签到码签名不可用", ex);
        }
    }
}
