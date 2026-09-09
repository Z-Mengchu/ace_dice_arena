package com.acedicearena.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 比赛状态全局 epoch 时钟：单调递增，与 GameStateRecord.version 无关
 *（player_roll / player_blind_box / match_guess 独立表写不会 bump version，
 * 但变更经 LobbyEventService 的比赛/大厅通知出口，在事务提交后 tick 本时钟）。
 * /api/game-state 用它实现 If-State-Version 条件请求（304）与 X-State-Version 响应头；
 * 单实例部署，内存时钟即可。
 */
@Component
public class StateVersionClock {
    private final AtomicLong epoch = new AtomicLong();

    public long tick() {
        return epoch.incrementAndGet();
    }

    public long current() {
        return epoch.get();
    }
}
