-- 仅供已经执行过旧版 schema.sql 的数据库升级使用；新部署无需执行。
USE ace_dice_arena;

ALTER TABLE user_account
    ADD COLUMN department VARCHAR(64) NOT NULL DEFAULT '未填写' AFTER display_name,
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER' AFTER department,
    ADD COLUMN team_id VARCHAR(4) NULL AFTER role,
    ADD COLUMN ready BOOLEAN NOT NULL DEFAULT FALSE AFTER team_id;

UPDATE user_account SET role = 'ADMIN', department = '赛事运营' WHERE username = 'admin';

CREATE TABLE game_control (
    id BIGINT NOT NULL,
    phase VARCHAR(16) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
