-- FlashSlot v1.1 数据库结构设计附件，2026-10-14。
-- 目标：MySQL 8.0.46 / InnoDB；所有 DATETIME(3) 按 UTC 读写。
-- 尚未在数据库执行验证；不是已部署的 Flyway migration。
-- 在隔离空库执行，迁移事务/版本由 Flyway 管理，禁止在现有业务库重复执行。
-- 不包含 CREATE DATABASE、DROP、TRUNCATE、真实账号或密码。
-- 所有业务表禁止物理删除账号、资源、订单；过期归档另行设计。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE app_user (
  id BIGINT NOT NULL AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'USER',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  credit_score SMALLINT NOT NULL DEFAULT 100,
  restriction_until DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_name (username),
  CONSTRAINT ck_user_role CHECK (role IN ('USER','ADMIN','DEMO')),
  CONSTRAINT ck_user_credit CHECK (credit_score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE resource (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  category VARCHAR(16) NOT NULL DEFAULT 'DEVICE',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  default_open_start_slot TINYINT UNSIGNED NOT NULL DEFAULT 16,
  default_open_end_slot TINYINT UNSIGNED NOT NULL DEFAULT 44,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_resource_list (status,category,id),
  CONSTRAINT ck_resource_status CHECK (status IN ('ACTIVE','PAUSED','RETIRED')),
  CONSTRAINT ck_resource_category CHECK (category IN ('DEVICE','ROOM','COMPUTE')),
  CONSTRAINT ck_resource_open CHECK (default_open_start_slot < default_open_end_slot AND default_open_end_slot <= 48)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE resource_day (
  resource_id BIGINT NOT NULL,
  book_date DATE NOT NULL,
  open_start_slot TINYINT UNSIGNED NOT NULL DEFAULT 16,
  open_end_slot TINYINT UNSIGNED NOT NULL DEFAULT 44,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  revision BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (resource_id,book_date),
  CONSTRAINT fk_day_resource FOREIGN KEY (resource_id) REFERENCES resource(id),
  CONSTRAINT ck_day_open CHECK (open_start_slot < open_end_slot AND open_end_slot <= 48),
  CONSTRAINT ck_day_revision CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_day_quota (
  user_id BIGINT NOT NULL,
  book_date DATE NOT NULL,
  used_slots SMALLINT NOT NULL DEFAULT 0,
  limit_slots SMALLINT NOT NULL DEFAULT 8,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id,book_date),
  CONSTRAINT fk_quota_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT ck_quota CHECK (used_slots >= 0 AND used_slots <= limit_slots AND limit_slots BETWEEN 1 AND 48)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE booking_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  book_date DATE NOT NULL,
  start_slot TINYINT UNSIGNED NOT NULL,
  end_slot TINYINT UNSIGNED NOT NULL,
  charged_slots SMALLINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',
  source VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
  purpose VARCHAR(32) NOT NULL,
  waitlist_id BIGINT NULL,
  release_job_id BIGINT NULL,
  start_at DATETIME(3) NOT NULL,
  check_in_open_at DATETIME(3) NOT NULL,
  check_in_deadline DATETIME(3) NOT NULL,
  end_at DATETIME(3) NOT NULL,
  checked_in_at DATETIME(3) NULL,
  terminated_at DATETIME(3) NULL,
  termination_reason VARCHAR(32) NULL,
  reason_text VARCHAR(256) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  UNIQUE KEY uk_order_waitlist (waitlist_id),
  UNIQUE KEY uk_order_release (release_job_id),
  KEY idx_order_timeout (status,check_in_deadline,id),
  KEY idx_order_complete (status,end_at,id),
  KEY idx_order_user_list (user_id,created_at DESC,id DESC),
  KEY idx_order_user_date (user_id,book_date,id),
  KEY idx_order_resource_date (resource_id,book_date,status,id),
  CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_order_day FOREIGN KEY (resource_id,book_date) REFERENCES resource_day(resource_id,book_date),
  CONSTRAINT ck_order_range CHECK (start_slot < end_slot AND end_slot <= 48 AND end_slot-start_slot <= 8),
  CONSTRAINT ck_order_charge CHECK (charged_slots >= 0 AND charged_slots <= end_slot-start_slot),
  CONSTRAINT ck_order_status CHECK (status IN ('CONFIRMED','CHECKED_IN','CANCELED','NO_SHOW','COMPLETED')),
  CONSTRAINT ck_order_purpose CHECK (purpose IN ('EXPERIMENT','STUDY','DEVICE_TEST')),
  CONSTRAINT ck_order_source CHECK ((source='DIRECT' AND waitlist_id IS NULL AND release_job_id IS NULL)
    OR (source='WAITLIST' AND waitlist_id IS NOT NULL AND release_job_id IS NOT NULL)),
  CONSTRAINT ck_order_time CHECK (check_in_open_at <= start_at AND start_at < check_in_deadline AND check_in_deadline < end_at),
  CONSTRAINT ck_order_checkin CHECK (status NOT IN ('CHECKED_IN','COMPLETED') OR checked_in_at IS NOT NULL),
  CONSTRAINT ck_order_reason CHECK (termination_reason IS NULL OR termination_reason IN ('USER_CANCEL','EARLY_FINISH','ADMIN_CANCEL','NO_SHOW')),
  CONSTRAINT ck_order_terminal CHECK ((status IN ('CANCELED','NO_SHOW') AND terminated_at IS NOT NULL AND termination_reason IS NOT NULL)
    OR (status NOT IN ('CANCELED','NO_SHOW') AND terminated_at IS NULL AND termination_reason IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE waitlist_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  waitlist_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  book_date DATE NOT NULL,
  start_slot TINYINT UNSIGNED NOT NULL,
  end_slot TINYINT UNSIGNED NOT NULL,
  purpose VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
  reason VARCHAR(64) NULL,
  expire_at DATETIME(3) NOT NULL,
  matched_booking_id BIGINT NULL,
  active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN status='WAITING' THEN 1 ELSE NULL END) STORED,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_waitlist_no (waitlist_no),
  UNIQUE KEY uk_waitlist_active (user_id,resource_id,book_date,start_slot,end_slot,active_marker),
  UNIQUE KEY uk_waitlist_booking (matched_booking_id),
  KEY idx_waitlist_match (resource_id,book_date,start_slot,end_slot,status,id),
  KEY idx_waitlist_expire (status,expire_at,id),
  KEY idx_waitlist_user_day (user_id,book_date,status),
  KEY idx_waitlist_user_list (user_id,created_at DESC,id DESC),
  CONSTRAINT fk_waitlist_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_waitlist_day FOREIGN KEY (resource_id,book_date) REFERENCES resource_day(resource_id,book_date),
  CONSTRAINT fk_waitlist_booking FOREIGN KEY (matched_booking_id) REFERENCES booking_order(id),
  CONSTRAINT ck_waitlist_range CHECK (start_slot < end_slot AND end_slot <= 48 AND end_slot-start_slot <= 8),
  CONSTRAINT ck_waitlist_purpose CHECK (purpose IN ('EXPERIMENT','STUDY','DEVICE_TEST')),
  CONSTRAINT ck_waitlist_status CHECK (status IN ('WAITING','MATCHED','CANCELED','EXPIRED','REJECTED')),
  CONSTRAINT ck_waitlist_matched CHECK ((status='MATCHED' AND matched_booking_id IS NOT NULL)
    OR (status<>'MATCHED' AND matched_booking_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE release_job (
  id BIGINT NOT NULL AUTO_INCREMENT,
  job_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_booking_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  book_date DATE NOT NULL,
  start_slot TINYINT UNSIGNED NOT NULL,
  end_slot TINYINT UNSIGNED NOT NULL,
  effective_start_slot TINYINT UNSIGNED NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  attempts SMALLINT NOT NULL DEFAULT 0,
  next_run_at DATETIME(3) NOT NULL,
  last_error VARCHAR(512) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_release_no (job_no),
  UNIQUE KEY uk_release_source (source_booking_id),
  KEY idx_release_due (status,next_run_at,id),
  KEY idx_release_day (resource_id,book_date,status),
  CONSTRAINT fk_release_source FOREIGN KEY (source_booking_id) REFERENCES booking_order(id),
  CONSTRAINT fk_release_day FOREIGN KEY (resource_id,book_date) REFERENCES resource_day(resource_id,book_date),
  CONSTRAINT ck_release_range CHECK (start_slot < end_slot AND end_slot <= 48 AND effective_start_slot BETWEEN start_slot AND end_slot),
  CONSTRAINT ck_release_attempt CHECK (attempts >= 0),
  CONSTRAINT ck_release_status CHECK (status IN ('PENDING','MATCHED','RELEASED','MANUAL_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 可空循环关系：先有 WAITING 和 PENDING，再插入新订单，最后更新 MATCHED。
ALTER TABLE booking_order
  ADD CONSTRAINT fk_order_waitlist FOREIGN KEY (waitlist_id) REFERENCES waitlist_record(id),
  ADD CONSTRAINT fk_order_release FOREIGN KEY (release_job_id) REFERENCES release_job(id);

CREATE TABLE slot_occupation (
  resource_id BIGINT NOT NULL,
  book_date DATE NOT NULL,
  slot_index TINYINT UNSIGNED NOT NULL,
  state VARCHAR(16) NOT NULL,
  booking_id BIGINT NULL,
  release_job_id BIGINT NULL,
  PRIMARY KEY (resource_id,book_date,slot_index),
  KEY idx_slot_booking (booking_id),
  KEY idx_slot_release (release_job_id),
  CONSTRAINT fk_slot_day FOREIGN KEY (resource_id,book_date) REFERENCES resource_day(resource_id,book_date),
  CONSTRAINT fk_slot_booking FOREIGN KEY (booking_id) REFERENCES booking_order(id),
  CONSTRAINT fk_slot_release FOREIGN KEY (release_job_id) REFERENCES release_job(id),
  CONSTRAINT ck_slot_index CHECK (slot_index < 48),
  CONSTRAINT ck_slot_owner CHECK ((state='BOOKED' AND booking_id IS NOT NULL AND release_job_id IS NULL)
    OR (state='HANDOFF' AND booking_id IS NULL AND release_job_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_slot_occupation (
  user_id BIGINT NOT NULL,
  book_date DATE NOT NULL,
  slot_index TINYINT UNSIGNED NOT NULL,
  booking_id BIGINT NOT NULL,
  PRIMARY KEY (user_id,book_date,slot_index),
  KEY idx_user_slot_booking (booking_id),
  CONSTRAINT fk_user_slot_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_user_slot_booking FOREIGN KEY (booking_id) REFERENCES booking_order(id),
  CONSTRAINT ck_user_slot_index CHECK (slot_index < 48)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE credit_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  business_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  delta SMALLINT NOT NULL,
  score_before SMALLINT NOT NULL,
  score_after SMALLINT NOT NULL,
  reason VARCHAR(256) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_credit_event (event_type,business_id),
  KEY idx_credit_user (user_id,id),
  CONSTRAINT fk_credit_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT ck_credit_type CHECK (event_type IN ('NO_SHOW','ADMIN_ADJUST')),
  CONSTRAINT ck_credit_range CHECK (score_before BETWEEN 0 AND 100 AND score_after BETWEEN 0 AND 100),
  CONSTRAINT ck_credit_delta CHECK (score_after = score_before + delta)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE api_idempotency (
  user_id BIGINT NOT NULL,
  operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash BINARY(32) NOT NULL,
  result_http_status SMALLINT NULL,
  result_code VARCHAR(64) NULL,
  result_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id,operation,request_key),
  KEY idx_idempotency_expire (expires_at),
  CONSTRAINT ck_idempotency_http CHECK (result_http_status IS NULL OR result_http_status BETWEEN 100 AND 599)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_event (
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'NEW',
  attempts SMALLINT NOT NULL DEFAULT 0,
  next_run_at DATETIME(3) NOT NULL,
  lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until DATETIME(3) NULL,
  last_error VARCHAR(512) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at DATETIME(3) NULL,
  PRIMARY KEY (event_id),
  KEY idx_outbox_due (status,next_run_at,event_id),
  KEY idx_outbox_lease (status,lease_until,event_id),
  CONSTRAINT ck_outbox_status CHECK (status IN ('NEW','SENDING','PUBLISHED','FAILED')),
  CONSTRAINT ck_outbox_attempt CHECK (attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE consumer_inbox (
  consumer_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (consumer_name,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  type VARCHAR(64) NOT NULL,
  payload JSON NOT NULL,
  read_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_notification_event (user_id,event_id,type),
  KEY idx_notification_user (user_id,created_at DESC,id DESC),
  CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE admin_audit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_id BIGINT NOT NULL,
  operation VARCHAR(64) NOT NULL,
  target_id VARCHAR(64) NOT NULL,
  reason VARCHAR(256) NOT NULL,
  trace_id VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_actor (actor_id,created_at,id),
  KEY idx_audit_time (created_at,id),
  CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 服务层还必须校验跨表同一 user/resource/date、区间归属及所有枚举变化。
-- 外键并不证明一个 booking_id 与 slot 的 resource/date/slot 匹配。
-- 配额总和、合法状态转换、decisionTime 和 FIFO 由事务/锁协议保证。
-- FK 会获取相关父表锁：并发集成测试必须使用本结构，不用删 FK 后的近似结构。
-- api_idempotency 不设用户 FK：它在资源锁之前写入，父表共享锁会改变业务锁序。
-- 其用户存在性由认证与后续用户锁检查；账号不物理删除，孤儿记录由归档检查发现。
