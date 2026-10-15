package com.flashslot.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.flashslot.common.persistence.UtcInstantTypeHandler;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 联合主键：user_id, operation, request_key；使用显式 SQL，不使用单主键 CRUD。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "api_idempotency", autoResultMap = true)
public class ApiIdempotency {
    /** 所属用户主键 */
    @TableField(value = "user_id")
    private Long userId;

    /** 固定操作类型，目标业务编号也纳入请求哈希 */
    @TableField(value = "operation")
    private String operation;

    /** 客户端幂等键，同一次意图重试复用 */
    @TableField(value = "request_key")
    private String requestKey;

    /** 规范化目标与请求参数的 SHA-256 */
    @TableField(value = "payload_hash")
    private byte[] payloadHash;

    /** 首次处理的 HTTP 状态码 */
    @TableField(value = "result_http_status")
    private Integer resultHttpStatus;

    /** 首次处理的业务码 */
    @TableField(value = "result_code")
    private String resultCode;

    /** 首次处理结果，非当前订单状态 */
    @TableField(value = "result_json", typeHandler = JacksonTypeHandler.class)
    private JsonNode resultJson;

    /** 创建时间，UTC */
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;

    /** 记录归档截止时间，UTC */
    @TableField(value = "expires_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant expiresAt;
}
