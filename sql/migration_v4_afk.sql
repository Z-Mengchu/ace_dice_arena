-- 已有数据库升级：增加真实玩家挂机状态。
USE ace_dice_arena;

ALTER TABLE user_account
    ADD COLUMN afk BOOLEAN NOT NULL DEFAULT FALSE AFTER ready;
