package com.acedicearena.repository;

import com.acedicearena.domain.MatchGuess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MatchGuessRepository extends JpaRepository<MatchGuess, Long> {
    Optional<MatchGuess> findByGameDayAndMatchIdAndGuessTypeAndRoundNoAndSideAndPlayerId(
            int gameDay, String matchId, String guessType, int roundNo, String side, String playerId);
    List<MatchGuess> findByGameDayAndMatchIdAndGuessTypeAndRoundNo(
            int gameDay, String matchId, String guessType, int roundNo);
    List<MatchGuess> findByGameDayAndMatchIdAndGuessType(int gameDay, String matchId, String guessType);
    List<MatchGuess> findByGameDayAndMatchId(int gameDay, String matchId);
}
