-- 已有数据库升级：猜阵热点修复。
-- match_guess：本轮猜阵(round)与提前猜阵(pre)按玩家独立成行，提交不再走 game_state 全局行锁；
-- (game_day, match_id, guess_type, round_no, side, player_id) 唯一键保证幂等（pre 改投 = 同键覆盖）；
-- 揭晓/开窗时合并回 game_state，视图只注入 guessStatus/preGuessStatus 提交状态布尔（内容保持密封）。
USE ace_dice_arena;

CREATE TABLE IF NOT EXISTS match_guess (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_day INT NOT NULL,
    match_id VARCHAR(8) NOT NULL,
    guess_type VARCHAR(8) NOT NULL,        -- 'round' / 'pre'
    round_no INT NOT NULL,                 -- round 猜填当前轮；pre 猜填目标轮
    side VARCHAR(1) NOT NULL,              -- 'A' / 'B'
    player_id VARCHAR(16) NOT NULL,
    targets VARCHAR(128) NOT NULL,         -- JSON 数组，5 个敌方 id
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_match_guess UNIQUE (game_day, match_id, guess_type, round_no, side, player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
