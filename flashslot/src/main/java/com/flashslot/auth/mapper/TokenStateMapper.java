package com.flashslot.auth.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TokenStateMapper {
    @Select("SELECT credential_version FROM app_user WHERE id=#{id} AND enabled=1")
    Long version(long id);

    @Select("SELECT EXISTS(SELECT 1 FROM revoked_token WHERE token_hash=#{hash})")
    boolean revoked(String hash);

    @Insert("INSERT INTO revoked_token(token_hash,expires_at) VALUES (#{hash},#{expiresAt}) ON DUPLICATE KEY UPDATE token_hash=VALUES(token_hash)")
    void revoke(@Param("hash") String hash,@Param("expiresAt") Instant expiresAt);
}
