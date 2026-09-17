-- 已有数据库升级：猜阵写入热点修复。
-- player_guess：统一猜阵窗口内每位队员的猜阵独立成行（单行 upsert，不碰 game_state 行锁）；
-- 揭晓（提前/到点/强制）时一次性读表结算 6 局并写回 game_state；
-- 视图层的 guessStatus 由快照加载时从本表注入，ETag 增加 guess 修订号。
USE ace_dice_arena;

CREATE TABLE IF NOT EXISTS player_guess (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_day INT NOT NULL,
    bracket_round INT NOT NULL,
    match_id VARCHAR(8) NOT NULL,
    player_id VARCHAR(16) NOT NULL,
    team_id VARCHAR(4) NOT NULL,
    side VARCHAR(1) NOT NULL,
    round_no INT NOT NULL,
    targets_json VARCHAR(128) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_player_guess_match_player UNIQUE (game_day, match_id, player_id),
    INDEX idx_player_guess_day_round (game_day, bracket_round)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
