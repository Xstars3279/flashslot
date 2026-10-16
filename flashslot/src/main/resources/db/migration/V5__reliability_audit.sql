-- FlashSlot：请求幂等、可靠消息、通知与管理员审计。
-- MySQL 8.0.16+ / InnoDB；先选择目标数据库，按 db 文件编号执行。
-- 面向空库的建表文件，不删除/覆盖已有表；存量变更需要独立迁移。
-- DATETIME 按 UTC 保存；业务日期按 Asia/Shanghai。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 同一用户同一操作同一请求键的结果回放。
CREATE TABLE api_idempotency (
  user_id BIGINT NOT NULL COMMENT '所属用户主键',
  operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定操作类型，目标业务编号也纳入请求哈希',
  request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端幂等键，同一次意图重试复用',
  payload_hash BINARY(32) NOT NULL COMMENT '规范化目标与请求参数的 SHA-256',
  result_http_status SMALLINT NULL COMMENT '首次处理的 HTTP 状态码',
  result_code VARCHAR(64) NULL COMMENT '首次处理的业务码',
  result_json JSON NULL COMMENT '首次处理结果，非当前订单状态',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  expires_at DATETIME(3) NOT NULL COMMENT '记录归档截止时间，UTC',
  PRIMARY KEY (user_id,operation,request_key),
  KEY idx_idempotency_expire (expires_at),
  CONSTRAINT ck_idempotency_http CHECK (result_http_status IS NULL OR result_http_status BETWEEN 100 AND 599)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同一用户同一操作同一请求键的结果回放';

-- 与业务事务一起持久化的待投递事件。
CREATE TABLE outbox_event (
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定事件 UUID，重试不变',
  event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件类型',
  aggregate_id VARCHAR(64) NOT NULL COMMENT '关联业务编号',
  payload JSON NOT NULL COMMENT '带 schemaVersion 的 JSON 数据',
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NEW' COMMENT '业务状态，合法值由本表 CHECK 约束限定',
  attempts SMALLINT NOT NULL DEFAULT 0 COMMENT '连续失败或投递尝试计数',
  next_run_at DATETIME(3) NOT NULL COMMENT '下一次处理时间，UTC',
  lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本次领取的随机租约令牌',
  lease_until DATETIME(3) NULL COMMENT '租约到期时间，UTC',
  last_error VARCHAR(512) NULL COMMENT '最近一次失败摘要，不写密钥',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  published_at DATETIME(3) NULL COMMENT 'broker 确认成功时间，非消费完成时间',
  PRIMARY KEY (event_id),
  KEY idx_outbox_due (status,next_run_at,event_id),
  KEY idx_outbox_lease (status,lease_until,event_id),
  CONSTRAINT ck_outbox_status CHECK (status IN ('NEW','SENDING','PUBLISHED','FAILED')),
  CONSTRAINT ck_outbox_attempt CHECK (attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='与业务事务一起持久化的待投递事件';

-- 通知消费完成记录，与通知同事务提交。
CREATE TABLE consumer_inbox (
  consumer_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '消费者身份',
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定事件 UUID，重试不变',
  processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事务提交的消费完成时间，UTC',
  PRIMARY KEY (consumer_name,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知消费完成记录，与通知同事务提交';

-- 用户站内通知。
CREATE TABLE notification (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部自增主键',
  user_id BIGINT NOT NULL COMMENT '所属用户主键',
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定事件 UUID，重试不变',
  type VARCHAR(64) NOT NULL COMMENT '通知类型',
  payload JSON NOT NULL COMMENT '带 schemaVersion 的 JSON 数据',
  read_at DATETIME(3) NULL COMMENT '已读时间，UTC',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  PRIMARY KEY (id),
  UNIQUE KEY uk_notification_event (user_id,event_id,type),
  KEY idx_notification_user (user_id,created_at DESC,id DESC),
  CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户站内通知';

-- 管理员操作审计。
CREATE TABLE admin_audit (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部自增主键',
  actor_id BIGINT NOT NULL COMMENT '管理员账号主键',
  operation VARCHAR(64) NOT NULL COMMENT '固定操作类型，目标业务编号也纳入请求哈希',
  target_id VARCHAR(64) NOT NULL COMMENT '被操作的业务身份',
  reason VARCHAR(256) NOT NULL COMMENT '拒绝或操作原因',
  trace_id VARCHAR(64) NULL COMMENT '链路追踪编号',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  PRIMARY KEY (id),
  KEY idx_audit_actor (actor_id,created_at,id),
  KEY idx_audit_time (created_at,id),
  CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员操作审计';

-- api_idempotency 不设用户外键：它在资源锁前写入，避免提前取得用户父表锁。
-- Inbox 只用于通知消费；释放依持久化任务状态幂等，缓存删除自身幂等。
