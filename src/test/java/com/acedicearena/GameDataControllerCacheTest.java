package com.acedicearena;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.BattleReportRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.MatchReportRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.LobbyEventService;
import com.acedicearena.service.ParallelTournamentService;
import com.acedicearena.web.AuthController;
import com.acedicearena.web.GameDataController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GameDataControllerCacheTest {
    @Test
    void simultaneousReadersReuseTheSameGameStateSnapshot() {
        GameStateRepository states = mock(GameStateRepository.class);
        GameStateRecord record = new GameStateRecord(1L, "{\"stage\":\"ACCUMULATION\"}", "test");
        when(states.findById(1L)).thenReturn(Optional.of(record));
        GameDataController controller = new GameDataController(states, mock(BattleReportRepository.class),
                new ObjectMapper(), mock(LobbyEventService.class), mock(UserAccountRepository.class),
                mock(com.acedicearena.service.ParallelTournamentService.class),
                mock(com.acedicearena.repository.MatchReportRepository.class), 1000);
        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("role", "ADMIN");

        assertThat(controller.getGameState(admin).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.getGameState(admin).getStatusCode().is2xxSuccessful()).isTrue();
        verify(states, times(1)).findById(1L);
    }

    @Test
    void ordinaryUserPublicViewIsCachedPerTeamAndInvalidatedWithSnapshot() {
        GameStateRepository states = mock(GameStateRepository.class);
        GameStateRecord record = new GameStateRecord(1L, "{\"stage\":\"ACCUMULATION\",\"teams\":[]}", "test");
        when(states.findById(1L)).thenReturn(Optional.of(record));
        UserAccountRepository users = mock(UserAccountRepository.class);
        UserAccount account = mock(UserAccount.class);
        when(account.getTeamId()).thenReturn("t1");
        when(users.findByUsername("alice")).thenReturn(Optional.of(account));
        ParallelTournamentService tournament = mock(ParallelTournamentService.class);
        when(tournament.publicStateView(any(), any(), any()))
                .thenAnswer(inv -> ((JsonNode) inv.getArgument(0)).deepCopy());
        GameDataController controller = new GameDataController(states, mock(BattleReportRepository.class),
                new ObjectMapper(), mock(LobbyEventService.class), users, tournament,
                mock(MatchReportRepository.class), 1000);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "USER");
        session.setAttribute(AuthController.SESSION_USER, "alice");

        assertThat(controller.getGameState(session).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.getGameState(session).getStatusCode().is2xxSuccessful()).isTrue();
        verify(tournament, times(1)).publicStateView(any(), any(), any());

        // 写入使快照失效后，下一次读取重新脱敏
        controller.saveGameState(new ObjectMapper().createObjectNode(), session);
        assertThat(controller.getGameState(session).getStatusCode().is2xxSuccessful()).isTrue();
        verify(tournament, times(2)).publicStateView(any(), any(), any());
    }
}
