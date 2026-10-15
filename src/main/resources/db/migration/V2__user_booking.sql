-- FlashSlot：用户与预约订单。
-- MySQL 8.0.16+ / InnoDB；先选择目标数据库，按 db 文件编号执行。
-- 面向空库的建表文件，不删除/覆盖已有表；存量变更需要独立迁移。
-- DATETIME 按 UTC 保存；业务日期按 Asia/Shanghai。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 账号、权限与当前信用状态。
CREATE TABLE app_user (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部自增主键',
  username VARCHAR(64) NOT NULL COMMENT '登录账号',
  password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt 或 Argon2 密码哈希，不存明文',
  role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER' COMMENT 'USER 用户，ADMIN 管理员，DEMO 演示',
  enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '账号是否启用',
  credit_score SMALLINT NOT NULL DEFAULT 100 COMMENT '当前信用分，范围 0 到 100',
  restriction_until DATETIME(3) NULL COMMENT '预约限制截止时间，UTC',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_name (username),
  CONSTRAINT ck_user_role CHECK (role IN ('USER','ADMIN','DEMO')),
  CONSTRAINT ck_user_credit CHECK (credit_score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号、权限与当前信用状态';

-- 完整预约业务记录，区间左闭右开。
CREATE TABLE booking_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部自增主键',
  order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对外随机业务订单号',
  user_id BIGINT NOT NULL COMMENT '所属用户主键',
  resource_id BIGINT NOT NULL COMMENT '所属设备主键',
  book_date DATE NOT NULL COMMENT '业务日期，Asia/Shanghai',
  start_slot TINYINT UNSIGNED NOT NULL COMMENT '起始格，包含',
  end_slot TINYINT UNSIGNED NOT NULL COMMENT '结束格，不包含',
  charged_slots SMALLINT NOT NULL COMMENT '当前计入每日使用量的格数，支持将来汇总配额',
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CONFIRMED' COMMENT '业务状态，合法值由本表 CHECK 约束限定',
  source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DIRECT' COMMENT 'DIRECT 直接预约，WAITLIST 候补转正',
  purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用途枚举',
  waitlist_id BIGINT NULL COMMENT '转正来源候补，仅候补订单有值',
  release_job_id BIGINT NULL COMMENT '交接任务主键',
  start_at DATETIME(3) NOT NULL COMMENT '预约开始时间，UTC',
  check_in_open_at DATETIME(3) NOT NULL COMMENT '签到窗口开始，UTC',
  check_in_deadline DATETIME(3) NOT NULL COMMENT '签到截止，不包含该时刻，UTC',
  end_at DATETIME(3) NOT NULL COMMENT '预约结束时间，UTC',
  checked_in_at DATETIME(3) NULL COMMENT '实际签到时间，UTC',
  terminated_at DATETIME(3) NULL COMMENT '取消或爽约判定时间，UTC',
  termination_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'USER_CANCEL、EARLY_FINISH、ADMIN_CANCEL 或 NO_SHOW',
  reason_text VARCHAR(256) NULL COMMENT '管理取消等操作说明',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '业务记录版本，由应用维护',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='完整预约业务记录，区间左闭右开';
