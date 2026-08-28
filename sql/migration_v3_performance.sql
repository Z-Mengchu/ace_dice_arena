-- 已部署旧版本时执行；全新部署只需执行 schema.sql。
USE ace_dice_arena;

ALTER TABLE user_account
    ADD COLUMN front_end BOOLEAN NOT NULL DEFAULT FALSE AFTER ready,
    ADD COLUMN gmv DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER front_end;

CREATE TABLE performance_record (
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
