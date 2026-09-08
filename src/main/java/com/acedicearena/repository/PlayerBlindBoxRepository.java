package com.acedicearena.repository;

import com.acedicearena.domain.PlayerBlindBox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerBlindBoxRepository extends JpaRepository<PlayerBlindBox, Long> {
    Optional<PlayerBlindBox> findByGameDayAndBracketRoundAndPlayerId(int gameDay, int bracketRound, String playerId);
    List<PlayerBlindBox> findByGameDayAndBracketRound(int gameDay, int bracketRound);
}
