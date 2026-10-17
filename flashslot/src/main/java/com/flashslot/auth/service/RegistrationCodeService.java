package com.flashslot.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.flashslot.common.web.BusinessException;

@Service @Slf4j
public class RegistrationCodeService {
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final SecureRandom random=new SecureRandom();
    private static final String PREFIX="flashslot:registration:";
    private static final DefaultRedisScript<Long> SEND=new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[2])==1 then return 1 end
        if tonumber(redis.call('GET',KEYS[3]) or '0')>=60 then return 2 end
        if tonumber(redis.call('GET',KEYS[4]) or '0')>=10 then return 2 end
        redis.call('SET',KEYS[2],'1','EX',60)
        if redis.call('INCR',KEYS[3])==1 then redis.call('EXPIRE',KEYS[3],3600) end
        if redis.call('INCR',KEYS[4])==1 then redis.call('EXPIRE',KEYS[4],86400) end
        redis.call('SET',KEYS[1],ARGV[1],'EX',300)
        redis.call('DEL',KEYS[5])
        return 0
        """,Long.class);
    private static final DefaultRedisScript<Long> VERIFY=new DefaultRedisScript<>("""
        local expected=redis.call('GET',KEYS[1])
        if not expected then return 0 end
        if expected==ARGV[1] then return 1 end
        local attempts=redis.call('INCR',KEYS[2])
        if attempts==1 then redis.call('EXPIRE',KEYS[2],math.max(1,redis.call('TTL',KEYS[1]))) end
        if attempts>=5 then redis.call('DEL',KEYS[1]) end
        return 0
        """,Long.class);
    private static final DefaultRedisScript<Long> CONSUME=new DefaultRedisScript<>("""
        if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1],KEYS[2]) end
        return 0
        """,Long.class);

    public RegistrationCodeService(StringRedisTemplate redis,@Value("${app.registration.development-code-enabled:false}") boolean enabled) {
        this.redis=redis; this.enabled=enabled;
    }
    public void requireEnabled() {
        if(!enabled) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"REGISTRATION_DISABLED","手机号注册尚未启用");
    }
    public boolean isEnabled() { return enabled; }
    public String send(String phone,String ip) {
        requireEnabled();
        String code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
        try {
            Long result=redis.execute(SEND,List.of(codeKey(phone),PREFIX+"cooldown:"+phone,PREFIX+"ip:"+hash(ip),PREFIX+"daily:"+phone,attemptKey(phone)),hash(phone+":"+code));
            if(result==null) throw unavailable();
            if(result!=0) throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"CODE_RATE_LIMIT","验证码获取频繁，请稍后重试（同一手机号间隔60秒，每24小时最多10次）");
            return code;
        } catch(DataAccessException ex) { throw unavailable(); }
    }
    public void verify(String phone,String code) {
        requireEnabled();
        try {
            if(!Long.valueOf(1).equals(redis.execute(VERIFY,List.of(codeKey(phone),attemptKey(phone)),hash(phone+":"+code))))
                throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_REGISTRATION_CODE","验证码错误或已失效，请重新获取");
        } catch(DataAccessException ex) { throw unavailable(); }
    }
    /** 数据库提交后才消费验证码；失败不回滚已注册账号，手机号唯一约束仍阻止重复注册。 */
    public void consume(String phone,String code) {
        try { redis.execute(CONSUME,List.of(codeKey(phone),attemptKey(phone)),hash(phone+":"+code)); }
        catch(DataAccessException ex) { log.warn("注册已提交，验证码清理暂不可用，等待TTL过期"); }
    }
    private String codeKey(String phone) { return PREFIX+"code:"+phone; }
    private String attemptKey(String phone) { return PREFIX+"attempts:"+phone; }
    private BusinessException unavailable() { return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"REGISTRATION_UNAVAILABLE","验证码服务暂不可用，请稍后重试"); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
