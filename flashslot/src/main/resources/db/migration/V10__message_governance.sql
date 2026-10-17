-- 留痕事件显式归档，不计入可投递积压；保留事件原文用于有限期核查。
ALTER TABLE outbox_event
  DROP CHECK ck_outbox_status,
  ADD CONSTRAINT ck_outbox_status CHECK (status IN ('NEW','SENDING','PUBLISHED','FAILED','ARCHIVED')),
  ADD KEY idx_outbox_history (status,created_at,event_id);
UPDATE outbox_event SET status='ARCHIVED'
WHERE status='NEW' AND event_type IN
  ('SCHEDULE_CHANGED','CREDIT_CHANGED','WAITLIST_CREATED','RELEASE_REQUESTED','RELEASE_COMPLETED','RELEASE_MATCHED');

-- 旧消息上下文不可推断，保留 UNKNOWN；新失败保存受控来源及原因分类，不保存异常原文。
ALTER TABLE dead_letter
  ADD COLUMN source_queue VARCHAR(128) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN original_exchange VARCHAR(128) NULL,
  ADD COLUMN routing_key VARCHAR(128) NULL,
  ADD COLUMN failure_category VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN failure_reason VARCHAR(256) NOT NULL DEFAULT '历史消息缺少上下文',
  ADD COLUMN broker_death_count BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN last_received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
UPDATE dead_letter SET last_received_at=received_at;
