-- 王牌攻守擂 · 骰子大亨
-- MySQL 8.0+ 初始化脚本
-- 执行示例：mysql -u root -p < sql/schema.sql

CREATE DATABASE IF NOT EXISTS ace_dice_arena
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE ace_dice_arena;

CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) NOT NULL,
    display_name VARCHAR(32) NOT NULL,
    department VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    team_id VARCHAR(4) NULL,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    afk BOOLEAN NOT NULL DEFAULT FALSE,
    front_end BOOLEAN NOT NULL DEFAULT FALSE,
    gmv DECIMAL(18,2) NOT NULL DEFAULT 0,
    password_hash VARCHAR(64) NOT NULL,
    salt VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_account_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS performance_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ranking_no INT NULL,
    department VARCHAR(64) NULL,
    group_name VARCHAR(64) NULL,
    leader VARCHAR(32) NOT NULL,
    order_count DECIMAL(18,2) NOT NULL DEFAULT 0,
    sales_quantity DECIMAL(18,2) NOT NULL DEFAULT 0,
    sales_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    last_week_sales_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    match_status VARCHAR(16) NOT NULL,
    matched_user_id BIGINT NULL,
    imported_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_performance_matched_user (matched_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS game_control (
    id BIGINT NOT NULL,
    phase VARCHAR(16) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS game_state (
    id BIGINT NOT NULL,
    content LONGTEXT NOT NULL,
    version BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(32) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS battle_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    content LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(32) NULL,
    PRIMARY KEY (id),
    INDEX idx_battle_report_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS request_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(512) NOT NULL,
    username VARCHAR(32) NULL,
    remote_address VARCHAR(64) NULL,
    status_code INT NOT NULL,
    duration_ms BIGINT NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_request_time (requested_at),
    INDEX idx_request_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- admin 账户不在 SQL 中保存固定密码。
-- 应用首次启动时会创建 admin，密码读取 ADMIN_PASSWORD 环境变量。
