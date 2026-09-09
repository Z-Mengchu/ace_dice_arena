package com.acedicearena.repository;

import com.acedicearena.domain.PlayerRoll;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerRollRepository extends JpaRepository<PlayerRoll, Long> {
    Optional<PlayerRoll> findByGameDayAndBracketRoundAndPlayerId(int gameDay, int bracketRound, String playerId);
    List<PlayerRoll> findByGameDayAndBracketRound(int gameDay, int bracketRound);
}
