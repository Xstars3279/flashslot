CREATE TABLE dead_letter (
  id BIGINT NOT NULL AUTO_INCREMENT,
  message_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  body LONGBLOB NOT NULL,
  event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  event_type VARCHAR(64) NOT NULL,
  error_code VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'FAILED',
  deliveries INT NOT NULL DEFAULT 1,
  received_at DATETIME(3) NOT NULL,
  recovered_at DATETIME(3) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_dead_message(message_hash),
  KEY idx_dead_status(status,id),
  KEY idx_dead_event(event_id),
  CONSTRAINT ck_dead_status CHECK (status IN ('FAILED','RECOVERED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
