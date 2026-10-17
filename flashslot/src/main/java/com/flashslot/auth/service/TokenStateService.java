package com.flashslot.auth.service;

import com.flashslot.auth.mapper.TokenStateMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** 持久化撤销只保存 SHA-256 指纹；重启和多实例使用同一份状态。 */
@Service
@RequiredArgsConstructor
public class TokenStateService {
    private final TokenStateMapper mapper;
    private final AccountBanCache bans;

    public boolean valid(Jwt token) {
        Number uid=token.getClaim("userId");
        Object claim=token.getClaim("credentialVersion");
        if(uid==null || !(claim instanceof Number version)) return false;
        if(bans.isBlocked(uid.longValue(),version.longValue())) return false;
        Long current=mapper.version(uid.longValue());
        return current!=null && current==version.longValue() && !mapper.revoked(hash(token.getTokenValue()));
    }

    public void revoke(Jwt token) { mapper.revoke(hash(token.getTokenValue()),token.getExpiresAt()); }

    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
