-- FlashSlot：候补、释放任务与设备及用户时间格占用。
-- MySQL 8.0.16+ / InnoDB；先选择目标数据库，按 db 文件编号执行。
-- 面向空库的建表文件，不删除/覆盖已有表；存量变更需要独立迁移。
-- DATETIME 按 UTC 保存；业务日期按 Asia/Shanghai。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 同设备同日期同区间的 FIFO 候补。
CREATE TABLE waitlist_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部自增主键',
  waitlist_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对外随机候补号',
  user_id BIGINT NOT NULL COMMENT '所属用户主键',
  resource_id BIGINT NOT NULL COMMENT '所属设备主键',
  book_date DATE NOT NULL COMMENT '业务日期，Asia/Shanghai',
  start_slot TINYINT UNSIGNED NOT NULL COMMENT '起始格，包含',
  end_slot TINYINT UNSIGNED NOT NULL COMMENT '结束格，不包含',
  purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用途枚举',
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'WAITING' COMMENT '业务状态，合法值由本表 CHECK 约束限定',
  reason VARCHAR(64) NULL COMMENT '拒绝或操作原因',
  expire_at DATETIME(3) NOT NULL COMMENT '候补转正截止时间，UTC',
  matched_booking_id BIGINT NULL COMMENT '已转正订单主键',
  active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN status='WAITING' THEN 1 ELSE NULL END) STORED COMMENT 'WAITING 时生成 1，终态为 NULL，用于只限制有效候补重复',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同设备同日期同区间的 FIFO 候补';

-- 取消或爽约后可恢复的候补交接任务。
CREATE TABLE release_job (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部自增主键',
  job_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对外随机交接任务号',
  source_booking_id BIGINT NOT NULL COMMENT '触发本次释放的旧订单主键',
  resource_id BIGINT NOT NULL COMMENT '所属设备主键',
  book_date DATE NOT NULL COMMENT '业务日期，Asia/Shanghai',
  start_slot TINYINT UNSIGNED NOT NULL COMMENT '起始格，包含',
  end_slot TINYINT UNSIGNED NOT NULL COMMENT '结束格，不包含',
  effective_start_slot TINYINT UNSIGNED NOT NULL COMMENT '仍可交接区间的起始格，只能向后移动',
  status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT '业务状态，合法值由本表 CHECK 约束限定',
  attempts SMALLINT NOT NULL DEFAULT 0 COMMENT '连续失败或投递尝试计数',
  next_run_at DATETIME(3) NOT NULL COMMENT '下一次处理时间，UTC',
  last_error VARCHAR(512) NULL COMMENT '最近一次失败摘要，不写密钥',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='取消或爽约后可恢复的候补交接任务';

-- 候补与任务先创建，随后补订单的循环外键；转正时先插订单再改候补状态。
ALTER TABLE booking_order
  ADD CONSTRAINT fk_order_waitlist FOREIGN KEY (waitlist_id) REFERENCES waitlist_record(id),
  ADD CONSTRAINT fk_order_release FOREIGN KEY (release_job_id) REFERENCES release_job(id);
-- 设备时间格占用，联合主键防重复分配。
CREATE TABLE slot_occupation (
  resource_id BIGINT NOT NULL COMMENT '所属设备主键',
  book_date DATE NOT NULL COMMENT '业务日期，Asia/Shanghai',
  slot_index TINYINT UNSIGNED NOT NULL COMMENT '30 分钟时间格编号，范围 0 到 47',
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'BOOKED 已分配，HANDOFF 候补交接中',
  booking_id BIGINT NULL COMMENT '精确归属订单主键',
  release_job_id BIGINT NULL COMMENT '交接任务主键',
  PRIMARY KEY (resource_id,book_date,slot_index),
  KEY idx_slot_booking (booking_id),
  KEY idx_slot_release (release_job_id),
  CONSTRAINT fk_slot_day FOREIGN KEY (resource_id,book_date) REFERENCES resource_day(resource_id,book_date),
  CONSTRAINT fk_slot_booking FOREIGN KEY (booking_id) REFERENCES booking_order(id),
  CONSTRAINT fk_slot_release FOREIGN KEY (release_job_id) REFERENCES release_job(id),
  CONSTRAINT ck_slot_index CHECK (slot_index < 48),
  CONSTRAINT ck_slot_owner CHECK ((state='BOOKED' AND booking_id IS NOT NULL AND release_job_id IS NULL)
    OR (state='HANDOFF' AND booking_id IS NULL AND release_job_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备时间格占用，联合主键防重复分配';

-- 用户时间格占用，防跨设备重叠预约。
CREATE TABLE user_slot_occupation (
  user_id BIGINT NOT NULL COMMENT '所属用户主键',
  book_date DATE NOT NULL COMMENT '业务日期，Asia/Shanghai',
  slot_index TINYINT UNSIGNED NOT NULL COMMENT '30 分钟时间格编号，范围 0 到 47',
  booking_id BIGINT NOT NULL COMMENT '精确归属订单主键',
  PRIMARY KEY (user_id,book_date,slot_index),
  KEY idx_user_slot_booking (booking_id),
  CONSTRAINT fk_user_slot_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_user_slot_booking FOREIGN KEY (booking_id) REFERENCES booking_order(id),
  CONSTRAINT ck_user_slot_index CHECK (slot_index < 48)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户时间格占用，防跨设备重叠预约';

-- 两张占用表逐格批量 INSERT，与订单和相关业务更新同事务提交。
-- 不用 INSERT IGNORE。释放/交接必须核对 booking_id 或 release_job_id。
-- 外键不自动检查跨表 user/resource/date 的匹配，应用仍需在锁内校验归属。
