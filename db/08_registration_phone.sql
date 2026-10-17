-- 存量账号保持不变，新增手机号注册唯一约束。
ALTER TABLE app_user
  ADD COLUMN phone VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '已注册手机号；历史及管理创建账号可为空',
  ADD UNIQUE KEY uk_user_phone (phone),
  ADD CONSTRAINT ck_user_phone CHECK (phone IS NULL OR phone REGEXP '^1[3-9][0-9]{9}$');
