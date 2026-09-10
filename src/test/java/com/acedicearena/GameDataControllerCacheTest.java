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

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GameDataControllerCacheTest {

    private GameDataController newController(GameStateRepository states, UserAccountRepository users,
                                          ParallelTournamentService tournament,
                                          com.acedicearena.service.BlindBoxRoundService blindBoxRounds) {
        return new GameDataController(states, mock(BattleReportRepository.class),
                new ObjectMapper(), mock(LobbyEventService.class), users, tournament,
                mock(MatchReportRepository.class),
                new com.acedicearena.service.GameStateSnapshotStore(states, new ObjectMapper(), blindBoxRounds),
                1000);
    }

    @Test
    void simultaneousReadersReuseTheSameGameStateSnapshot() {
        GameStateRepository states = mock(GameStateRepository.class);
        GameStateRecord record = new GameStateRecord(1L, "{\"stage\":\"ACCUMULATION\"}", "test");
        when(states.findById(1L)).thenReturn(Optional.of(record));
        when(states.findVersionById(1L)).thenAnswer(ignored -> Optional.of(record.getVersion()));
        GameDataController controller = newController(states, mock(UserAccountRepository.class),
                mock(com.acedicearena.service.ParallelTournamentService.class),
                mock(com.acedicearena.service.BlindBoxRoundService.class));
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
        when(states.findVersionById(1L)).thenAnswer(ignored -> Optional.of(record.getVersion()));
        UserAccountRepository users = mock(UserAccountRepository.class);
        UserAccount account = mock(UserAccount.class);
        when(account.getTeamId()).thenReturn("t1");
        when(users.findByUsername("alice")).thenReturn(Optional.of(account));
        ParallelTournamentService tournament = mock(ParallelTournamentService.class);
        when(tournament.publicStateView(any(), any(), any()))
                .thenAnswer(inv -> ((JsonNode) inv.getArgument(0)).deepCopy());
        GameDataController controller = newController(states, users, tournament,
                mock(com.acedicearena.service.BlindBoxRoundService.class));
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

    @Test
    void matchingGameStateEtagReturnsNotModifiedWithoutBuildingAnotherView() {
        GameStateRepository states = mock(GameStateRepository.class);
        GameStateRecord record = new GameStateRecord(1L, "{\"stage\":\"ROLL\",\"teams\":[]}", "test");
        when(states.findById(1L)).thenReturn(Optional.of(record));
        when(states.findVersionById(1L)).thenReturn(Optional.of(record.getVersion()));
        GameDataController controller = newController(states, mock(UserAccountRepository.class),
                mock(ParallelTournamentService.class),
                mock(com.acedicearena.service.BlindBoxRoundService.class));
        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("role", "ADMIN");

        var first = controller.getGameState(null, null, admin);
        String etag = first.getHeaders().getETag();
        var unchanged = controller.getGameState(null, etag, admin);

        assertThat(etag).isEqualTo("\"game-state-1-0-admin\"");
        assertThat(unchanged.getStatusCode().value()).isEqualTo(304);
        assertThat(unchanged.getBody()).isNull();
        verify(states, times(1)).findById(1L);
    }

    @Test
    void firstBlindBoxOpenInvalidatesOldEtagAndIdempotentRepeatKeepsIt() {
        GameStateRepository states = mock(GameStateRepository.class);
        GameStateRecord record = new GameStateRecord(1L, "{\"stage\":\"BLIND_BOX\",\"teams\":[]}", "test");
        when(states.findById(1L)).thenReturn(Optional.of(record));
        when(states.findVersionById(1L)).thenReturn(Optional.of(record.getVersion()));
        java.util.concurrent.atomic.AtomicLong blindRevision = new java.util.concurrent.atomic.AtomicLong();
        var blindBoxRounds = mock(com.acedicearena.service.BlindBoxRoundService.class);
        when(blindBoxRounds.revision()).thenAnswer(inv -> blindRevision.get());
        GameDataController controller = newController(states, mock(UserAccountRepository.class),
                mock(ParallelTournamentService.class), blindBoxRounds);
        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("role", "ADMIN");

        var before = controller.getGameState(null, null, admin);
        String etagBefore = before.getHeaders().getETag();
        assertThat(etagBefore).isEqualTo("\"game-state-1-0-admin\"");
        assertThat(controller.getGameState(null, etagBefore, admin).getStatusCode().value()).isEqualTo(304);

        // 首次开盒提交：blind revision +1，旧 ETag 回源得到 200 与新 ETag；body 的 version 仍是状态版本 long
        blindRevision.incrementAndGet();
        var after = controller.getGameState(null, etagBefore, admin);
        assertThat(after.getStatusCode().value()).isEqualTo(200);
        assertThat(after.getHeaders().getETag()).isEqualTo("\"game-state-1-1-admin\"");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) after.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("version")).isEqualTo(1L);

        // 幂等重复不改变 revision：ETag 不变，条件请求继续 304
        assertThat(controller.getGameState(null, "\"game-state-1-1-admin\"", admin)
                .getStatusCode().value()).isEqualTo(304);
        verify(states, times(2)).findById(1L);
    }
}
