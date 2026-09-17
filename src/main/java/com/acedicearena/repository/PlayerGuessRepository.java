package com.acedicearena.repository;

import com.acedicearena.domain.PlayerGuess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlayerGuessRepository extends JpaRepository<PlayerGuess, Long> {
    /** 单场全部猜阵行：揭晓结算与视图注入用，命中 uk 前缀 (game_day, match_id)。 */
    List<PlayerGuess> findByGameDayAndMatchId(int gameDay, String matchId);

    Optional<PlayerGuess> findByGameDayAndMatchIdAndPlayerId(int gameDay, String matchId, String playerId);

    long deleteByGameDayAndMatchIdAndPlayerId(int gameDay, String matchId, String playerId);

    /**
     * 重赛/新一轮开始前定向清理：只删当前在赛玩家复用唯一键的旧猜阵，
     * 与新 ROLL 状态在同一事务；不动其他历史比赛的行。
     */
    long deleteByGameDayAndBracketRoundAndPlayerIdIn(int gameDay, int bracketRound, Collection<String> playerIds);
}
