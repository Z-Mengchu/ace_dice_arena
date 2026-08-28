package com.acedicearena;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.repository.BattleReportRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.LobbyEventService;
import com.acedicearena.web.GameDataController;
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
                new ObjectMapper(), mock(LobbyEventService.class), mock(UserAccountRepository.class), 1000);
        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("role", "ADMIN");

        assertThat(controller.getGameState(admin).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.getGameState(admin).getStatusCode().is2xxSuccessful()).isTrue();
        verify(states, times(1)).findById(1L);
    }
}
