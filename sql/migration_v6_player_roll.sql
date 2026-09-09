-- 已有数据库升级：真人掷骰热点修复。
-- player_roll：掷骰结果按玩家独立成行，掷骰请求不再走 game_state 全局行锁；
-- (game_day, bracket_round, player_id) 唯一键保证幂等；推进 BLIND_BOX 时合并回 game_state。
USE ace_dice_arena;

CREATE TABLE IF NOT EXISTS player_roll (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_day INT NOT NULL,
    bracket_round INT NOT NULL,
    player_id VARCHAR(16) NOT NULL,
    team_id VARCHAR(4) NOT NULL,
    dice INT NOT NULL,
    dice_final INT NOT NULL,
    roll_ts BIGINT NOT NULL,
    auto_rolled TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_player_roll_round UNIQUE (game_day, bracket_round, player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
