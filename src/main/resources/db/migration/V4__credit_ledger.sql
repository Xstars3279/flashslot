-- FlashSlot：信用变更账本。
-- MySQL 8.0.16+ / InnoDB；先选择目标数据库，按 db 文件编号执行。
-- 面向空库的建表文件，不删除/覆盖已有表；存量变更需要独立迁移。
-- DATETIME 按 UTC 保存；业务日期按 Asia/Shanghai。
SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 信用变更账本，每个业务事件仅记一次。
CREATE TABLE credit_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部自增主键',
  user_id BIGINT NOT NULL COMMENT '所属用户主键',
  event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件类型',
  business_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务事件身份，防止重复信用变更',
  delta SMALLINT NOT NULL COMMENT '实际分值差，可为 0',
  score_before SMALLINT NOT NULL COMMENT '变更前分值',
  score_after SMALLINT NOT NULL COMMENT '变更后分值',
  reason VARCHAR(256) NOT NULL COMMENT '拒绝或操作原因',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  PRIMARY KEY (id),
  UNIQUE KEY uk_credit_event (event_type,business_id),
  KEY idx_credit_user (user_id,id),
  CONSTRAINT fk_credit_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT ck_credit_type CHECK (event_type IN ('NO_SHOW','ADMIN_ADJUST')),
  CONSTRAINT ck_credit_range CHECK (score_before BETWEEN 0 AND 100 AND score_after BETWEEN 0 AND 100),
  CONSTRAINT ck_credit_delta CHECK (score_after = score_before + delta)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='信用变更账本，每个业务事件仅记一次';
