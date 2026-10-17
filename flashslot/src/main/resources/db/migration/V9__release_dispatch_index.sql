-- 补偿扫描按事件类型、任务编号和半小时边界查重，避免历史 Outbox 增长后全表扫描。
ALTER TABLE outbox_event ADD KEY idx_outbox_aggregate (event_type,aggregate_id,created_at);
