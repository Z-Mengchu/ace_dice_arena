-- 已有数据库升级：盲盒开盒热点修复（两张表一次执行）。
-- 1) match_report：完赛对局逐局明细归档，game_state 行内只保留 {round, winner} 摘要，缩短全局行锁临界区。
-- 2) player_blind_box：开盲盒结果独立成行，开盒请求先锁定 game_state 再写入本表；
--    状态行锁实现正常幂等，唯一键保留为完整性防线；推进 TACTICS 时合并回 game_state。
USE ace_dice_arena;

CREATE TABLE IF NOT EXISTS match_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_day INT NOT NULL,
    match_id VARCHAR(8) NOT NULL,
    content LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_match_report_day_match (game_day, match_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_blind_box (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_day INT NOT NULL,
    bracket_round INT NOT NULL,
    player_id VARCHAR(16) NOT NULL,
    team_id VARCHAR(4) NOT NULL,
    box_value INT NOT NULL,
    opened_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_player_blind_box_round UNIQUE (game_day, bracket_round, player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
