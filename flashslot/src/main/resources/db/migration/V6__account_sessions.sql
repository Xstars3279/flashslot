ALTER TABLE app_user ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE revoked_token (
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY(token_hash),
  KEY idx_revoked_expiry(expires_at)
) ENGINE=InnoDB;
