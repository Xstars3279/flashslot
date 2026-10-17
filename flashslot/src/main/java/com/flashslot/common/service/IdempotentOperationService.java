package com.flashslot.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashslot.common.entity.ApiIdempotency;
import com.flashslot.common.mapper.ApiIdempotencyMapper;
import com.flashslot.common.web.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;

/** 幂等公共方法，只参与调用方的业务事务，不接管业务流程。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotentOperationService {
    private final ApiIdempotencyMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;

    public ApiIdempotency prepare(long userId, String operation, String key, String canonical) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,64}"))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "幂等键格式不合法");
        byte[] hash;
        try {
            hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        mapper.acquireOperation(
                userId, operation, key, hash, clock.instant().plus(Duration.ofDays(7)));
        var record = mapper.lockOperation(userId, operation, key);
        if (!MessageDigest.isEqual(hash, record.getPayloadHash()))
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "此请求键已用于其他参数");
        // 兼容旧版本错误缓存：仅清除写业务前产生的“日历未就绪”503，先核对请求哈希。
        if(Integer.valueOf(503).equals(record.getResultHttpStatus())
                && "CALENDAR_NOT_READY".equals(record.getResultCode())
                && ("CREATE_BOOKING".equals(operation) || "CREATE_WAITLIST".equals(operation))) {
            mapper.clearCalendarFailure(userId,operation,key);
            record.setResultHttpStatus(null); record.setResultCode(null); record.setResultJson(null);
        }
        return record;
    }

    /** 调用前必须在业务层重新检查当前账号和目标数据的访问权限。 */
    public <T> T replay(ApiIdempotency record, Class<T> type) {
        if (record.getResultHttpStatus() >= 400) {
            var body = record.getResultJson();
            String message =
                    body.has("msg") ? body.path("msg").asText() : body.path("message").asText();
            throw new BusinessException(
                    HttpStatus.valueOf(record.getResultHttpStatus()),
                    record.getResultCode(),
                    message);
        }
        return json.convertValue(record.getResultJson().get("data"), type);
    }

    public void saveSuccess(ApiIdempotency record, int status, Object data) {
        save(record, status, "SUCCESS", Result.success(data));
    }

    public void saveRejection(ApiIdempotency record, BusinessException error) {
        // 暂时性服务错误必须回滚本次事务，不能被 CommittedBusinessException 固化七天。
        if(error.getStatus().is5xxServerError())
            throw new BusinessException(error.getStatus(),error.getCode(),error.getMessage());
        save(record, error.getStatus().value(), error.getCode(), Result.error(error.getCode(),error.getMessage()));
    }

    private void save(ApiIdempotency record, int status, String code, Object body) {
        if (mapper.completeOperation(
                        record.getUserId(),
                        record.getOperation(),
                        record.getRequestKey(),
                        status,
                        code,
                        json.valueToTree(body).toString())
                != 1) throw new IllegalStateException("幂等结果保存失败");
    }
}
