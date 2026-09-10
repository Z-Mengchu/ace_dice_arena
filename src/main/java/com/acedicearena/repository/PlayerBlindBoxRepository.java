package com.acedicearena.repository;

import com.acedicearena.domain.PlayerBlindBox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlayerBlindBoxRepository extends JpaRepository<PlayerBlindBox, Long> {
    Optional<PlayerBlindBox> findByGameDayAndBracketRoundAndPlayerId(int gameDay, int bracketRound, String playerId);
    List<PlayerBlindBox> findByGameDayAndBracketRound(int gameDay, int bracketRound);

    /**
     * 开盒判定用：只数仍在名册内的玩家，命中唯一键 uk_player_blind_box_round 的 (game_day, bracket_round, player_id) 前缀。
     * 免掉的只是判定这一次的整轮行加载；判定为真后合并回 JSON（{@link #findByGameDayAndBracketRound}）
     * 与只读视图注入仍会物化整轮行。
     */
    long countByGameDayAndBracketRoundAndPlayerIdIn(int gameDay, int bracketRound, Collection<String> playerIds);
}
