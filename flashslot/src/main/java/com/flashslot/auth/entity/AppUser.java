package com.flashslot.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.flashslot.common.persistence.UtcInstantTypeHandler;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 数据库表 app_user 的持久化实体。 */
@Getter
@Setter
@NoArgsConstructor
@TableName(value = "app_user", autoResultMap = true)
public class AppUser {
    @TableField("phone")
    private String phone;
    @TableField("credential_version")
    private Long credentialVersion;
    /** 内部自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 登录账号 */
    @TableField(value = "username")
    private String username;

    /** BCrypt 或 Argon2 密码哈希，不存明文 */
    @TableField(value = "password_hash")
    private String passwordHash;

    /** USER 用户，ADMIN 管理员，DEMO 演示 */
    @TableField(value = "role")
    private String role;

    /** 账号是否启用 */
    @TableField(value = "enabled")
    private Boolean enabled;

    /** 当前信用分，范围 0 到 100 */
    @TableField(value = "credit_score")
    private Integer creditScore;

    /** 预约限制截止时间，UTC */
    @TableField(value = "restriction_until", typeHandler = UtcInstantTypeHandler.class)
    private Instant restrictionUntil;

    /** 创建时间，UTC */
    @TableField(value = "created_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;

    /** 更新时间，UTC */
    @TableField(value = "updated_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant updatedAt;
}
