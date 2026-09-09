package com.acedicearena;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.BattleReportRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.MatchReportRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.LobbyEventService;
import com.acedicearena.service.ParallelTournamentService;
import com.acedicearena.service.StateVersionClock;
import com.acedicearena.web.AuthController;
import com.acedicearena.web.GameDataController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GameDataControllerCacheTest {
    private static GameDataController controller(GameStateRepository states, UserAccountRepository users,
                                                 ParallelTournamentService tournament, MatchReportRepository matchReports,
                                                 StateVersionClock clock, long ttlMs) {
        return new GameDataController(states, mock(BattleReportRepository.class),
                new ObjectMapper(), mock(LobbyEventService.class), users, tournament, matchReports, clock, ttlMs);
    }

    private static MockHttpSession adminSession() {
        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("role", "ADMIN");
        return admin;
    }

    @Test
    void simultaneousReadersReuseTheSameGameStateSnapshot() {
        GameStateRepository states = mock(GameStateRepository.class);
        GameStateRecord record = new GameStateRecord(1L, "{\"stage\":\"ACCUMULATION\"}", "test");
        when(states.findById(1L)).thenReturn(Optional.of(record));
        GameDataController controller = controller(states, mock(UserAccountRepository.class),
                mock(ParallelTournamentService.class), mock(MatchReportRepository.class),
                new StateVersionClock(), 1000);
        MockHttpSession admin = adminSession();

        assertThat(controller.getGameState(null, admin).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.getGameState(null, admin).getStatusCode().is2xxSuccessful()).isTrue();
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
        GameDataController controller = controller(states, users, tournament,
                mock(MatchReportRepository.class), new StateVersionClock(), 1000);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "USER");
        session.setAttribute(AuthController.SESSION_USER, "alice");

        assertThat(controller.getGameState(null, session).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.getGameState(null, session).getStatusCode().is2xxSuccessful()).isTrue();
        verify(tournament, times(1)).publicStateView(any(), any(), any());

        // 写入使快照失效后，下一次读取重新脱敏
        controller.saveGameState(new ObjectMapper().createObjectNode(), session);
        assertThat(controller.getGameState(null, session).getStatusCode().is2xxSuccessful()).isTrue();
        verify(tournament, times(2)).publicStateView(any(), any(), any());
    }

    @Test
    void sameEpochConditionalRequestReturns304AndTickInvalidatesIt() {
        GameStateRepository states = mock(GameStateRepository.class);
        GameStateRecord record = new GameStateRecord(1L, "{\"stage\":\"ACCUMULATION\"}", "test");
        when(states.findById(1L)).thenReturn(Optional.of(record));
        StateVersionClock clock = new StateVersionClock();
        // ttl=0：每次请求都回源重组装快照，但 epoch 未变时 304 语义仍须成立
        GameDataController controller = controller(states, mock(UserAccountRepository.class),
                mock(ParallelTournamentService.class), mock(MatchReportRepository.class), clock, 0);
        MockHttpSession admin = adminSession();

        ResponseEntity<String> first = controller.getGameState(null, admin);
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        String epoch = first.getHeaders().getFirst("X-State-Version");
        assertThat(epoch).isNotNull();

        ResponseEntity<String> second = controller.getGameState(epoch, admin);
        assertThat(second.getStatusCode().value()).isEqualTo(304);
        assertThat(second.getBody()).isNull();

        // 独立表写不 bump game_state.version，但变更通知出口会 tick epoch
        clock.tick();
        ResponseEntity<String> third = controller.getGameState(epoch, admin);
        assertThat(third.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(third.getHeaders().getFirst("X-State-Version")).isNotEqualTo(epoch);
        assertThat(third.getBody()).contains("\"stage\":\"ACCUMULATION\"");
    }

    @Test
    void lobbyEventNotificationEntryPointsTickTheEpoch() {
        StateVersionClock clock = new StateVersionClock();
        LobbyEventService events = new LobbyEventService(clock);
        try {
            long start = clock.current();
            // 比赛和大厅变更均须推进 epoch，包含冠军产生与重置。
            events.gameChanged();
            events.gameChangedNow();
            events.teamGameChanged("t1");
            events.adminGameChanged();
            events.stateChanged();
            assertThat(clock.current()).isEqualTo(start + 5);
        } finally {
            events.close();
        }
    }

    @Test
    void committedLobbyChangeInvalidatesSnapshotEvenWithinTtl() {
        GameStateRepository states = mock(GameStateRepository.class);
        when(states.findById(1L)).thenReturn(
                Optional.of(new GameStateRecord(1L, "{\"stage\":\"BATTLE\"}", "test")),
                Optional.of(new GameStateRecord(1L, "{\"champion\":\"t1\"}", "test")));
        StateVersionClock clock = new StateVersionClock();
        GameDataController controller = controller(states, mock(UserAccountRepository.class),
                mock(ParallelTournamentService.class), mock(MatchReportRepository.class), clock, 60_000);
        String version = controller.getGameState(null, adminSession()).getHeaders().getFirst("X-State-Version");
        LobbyEventService events = new LobbyEventService(clock);
        try {
            events.stateChanged();
            ResponseEntity<String> next = controller.getGameState(version, adminSession());
            assertThat(next.getStatusCode().value()).isEqualTo(200);
            assertThat(next.getBody()).contains("\"champion\":\"t1\"");
            assertThat(next.getHeaders().getFirst("X-State-Version")).isNotEqualTo(version);
        } finally {
            events.close();
        }
    }

    @Test
    void commitDuringSnapshotReadCannotLabelOldContentWithNewVersion() {
        GameStateRepository states = mock(GameStateRepository.class);
        StateVersionClock clock = new StateVersionClock();
        when(states.findById(1L)).thenAnswer(inv -> {
            // 模拟已读出旧行后另一个事务提交，再继续组装快照。
            clock.tick();
            return Optional.of(new GameStateRecord(1L, "{\"stage\":\"ROLL\"}", "test"));
        }).thenReturn(Optional.of(new GameStateRecord(1L, "{\"stage\":\"BLIND_BOX\"}", "test")));
        GameDataController controller = controller(states, mock(UserAccountRepository.class),
                mock(ParallelTournamentService.class), mock(MatchReportRepository.class), clock, 60_000);
        ResponseEntity<String> old = controller.getGameState(null, adminSession());
        String version = old.getHeaders().getFirst("X-State-Version");
        assertThat(version).isEqualTo("0");
        ResponseEntity<String> fresh = controller.getGameState(version, adminSession());
        assertThat(fresh.getStatusCode().value()).isEqualTo(200);
        assertThat(fresh.getBody()).contains("BLIND_BOX");
        assertThat(fresh.getHeaders().getFirst("X-State-Version")).isEqualTo("1");
    }

    @Test
    void serializedViewBodiesAreCachedPerTeamAndSharedAcrossClients() throws Exception {
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
        GameDataController controller = controller(states, users, tournament,
                mock(MatchReportRepository.class), new StateVersionClock(), 1000);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "USER");
        session.setAttribute(AuthController.SESSION_USER, "alice");

        ResponseEntity<String> first = controller.getGameState(null, session);
        ResponseEntity<String> second = controller.getGameState(null, session);
        // 同一快照周期内同队客户端共享同一份序列化字节，脱敏只执行一次
        assertThat(second.getBody()).isEqualTo(first.getBody());
        verify(tournament, times(1)).publicStateView(any(), any(), any());
        JsonNode parsed = new ObjectMapper().readTree(first.getBody());
        assertThat(parsed.path("state").path("stage").asText()).isEqualTo("ACCUMULATION");
        assertThat(parsed.has("version")).isTrue();
    }

    @Test
    void matchDetailCarriesStateVersionHeaderAndCachesSerializedBody() {
        GameStateRepository states = mock(GameStateRepository.class);
        GameStateRecord record = new GameStateRecord(1L,
                "{\"stage\":\"BATTLE\",\"matches\":{\"m1\":{\"id\":\"m1\",\"rounds\":[{\"powerA\":1}]}}}", "test");
        when(states.findById(1L)).thenReturn(Optional.of(record));
        GameDataController controller = controller(states, mock(UserAccountRepository.class),
                mock(ParallelTournamentService.class), mock(MatchReportRepository.class),
                new StateVersionClock(), 1000);
        MockHttpSession admin = adminSession();

        ResponseEntity<String> first = controller.getMatchDetail("m1", admin);
        ResponseEntity<String> second = controller.getMatchDetail("m1", admin);
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(first.getHeaders().getFirst("X-State-Version")).isNotNull();
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(first.getBody()).contains("\"id\":\"m1\"");
        assertThat(controller.getMatchDetail("missing", admin).getStatusCode().value()).isEqualTo(404);
    }
}
