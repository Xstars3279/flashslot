-- FlashSlot：设备表与设备日历表。
-- 目标：MySQL 8.0.16+（支持执行 CHECK 约束），InnoDB。
-- 执行前先选择目标数据库；本文件不创建数据库、不删除或覆盖已有表。
-- 按文件顺序建表。若同名表已存在，应核对结构并编写迁移，不重复执行本文件。
-- 时间字段按 UTC 保存；业务日期 book_date 按 Asia/Shanghai 确定。
-- 已在私有临时 MySQL 中验证；没有在用户现有/目标数据库执行建表。

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 1. 设备：一条记录代表一台可独立预约的实物设备。
CREATE TABLE resource (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '设备主键',
    name VARCHAR(128) NOT NULL COMMENT '设备名称，例如 3D 打印机 01',
    location VARCHAR(128) NOT NULL COMMENT '设备位置，例如实验楼 A201',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 正常；PAUSED 暂停新申请；RETIRED 退役',
    default_open_start_slot TINYINT UNSIGNED NOT NULL DEFAULT 16
        COMMENT '默认开放起始格，包含；16 对应 08:00',
    default_open_end_slot TINYINT UNSIGNED NOT NULL DEFAULT 44
        COMMENT '默认开放结束格，不包含；44 对应 22:00',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '配置乐观锁版本，成功修改时由应用加 1',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
    PRIMARY KEY (id),
    KEY idx_resource_status_id (status, id),
    CONSTRAINT ck_resource_status CHECK (status IN ('ACTIVE', 'PAUSED', 'RETIRED')),
    CONSTRAINT ck_resource_open_range CHECK (
        default_open_start_slot < default_open_end_slot
        AND default_open_end_slot <= 48
    ),
    CONSTRAINT ck_resource_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='可独立预约的设备';

-- 2. 日历：同设备同日期一条记录，提前生成，作为当天业务写入的行锁目标。
-- 每天只有一个连续开放区间，不支持上午/下午分段。
CREATE TABLE resource_day (
    resource_id BIGINT NOT NULL COMMENT '所属设备主键',
    book_date DATE NOT NULL COMMENT '业务日期，Asia/Shanghai',
    open_start_slot TINYINT UNSIGNED NOT NULL DEFAULT 16
        COMMENT '当天开放起始格，包含；默认 08:00',
    open_end_slot TINYINT UNSIGNED NOT NULL DEFAULT 44
        COMMENT '当天开放结束格，不包含；默认 22:00',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '当天是否开放：1 是，0 否',
    revision BIGINT NOT NULL DEFAULT 0 COMMENT '当天排期版本，相关业务变更时由应用加 1',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
    PRIMARY KEY (resource_id, book_date),
    CONSTRAINT fk_resource_day_resource FOREIGN KEY (resource_id) REFERENCES resource (id),
    CONSTRAINT ck_resource_day_open_range CHECK (
        open_start_slot < open_end_slot
        AND open_end_slot <= 48
    ),
    CONSTRAINT ck_resource_day_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_resource_day_revision CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='设备每日开放日历与行锁目标';

-- 默认开放规则只用于生成新的日历；修改设备默认值不会自动改写既有日历。
-- 应用生成日历时显式复制设备的默认起止格，不依赖日历字段的固定 DEFAULT。
-- version/revision 不会自动递增：应用必须在对应业务事务中更新。
-- 预约时在事务中通过联合主键执行 SELECT ... FOR UPDATE，且日历行必须已存在。
