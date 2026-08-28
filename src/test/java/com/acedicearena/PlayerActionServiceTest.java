package com.acedicearena;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.GameControlRepository;
import com.acedicearena.repository.PerformanceRecordRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.LobbyEventService;
import com.acedicearena.service.PlayerActionService;
import com.acedicearena.service.OnlineGameService;
import com.acedicearena.service.ParallelTournamentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlayerActionServiceTest {
    @Test
    void opposingTeamCanPrepareAndReceiveCountdownWhileFirstTeamIsAlreadyTiming() throws Exception {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        LobbyEventService events = mock(LobbyEventService.class);
        OnlineGameService online = mock(OnlineGameService.class);
        ObjectMapper mapper = new ObjectMapper();
        UserAccount playerB = new UserAccount("parallel_b", "并行队员", "业务部", "USER", "hash", "salt");
        playerB.assignTeam("t2");
        when(users.findByUsername("parallel_b")).thenReturn(Optional.of(playerB));
        String json = """
                {"mode":"parallel","stage":"ATTACK","teams":[
                  {"id":"t1","roles":{"captain":"unull","pitcher":"unull"}},
                  {"id":"t2","roles":{"captain":"unull","pitcher":"unull"}}
                ],"matches":{"g1":{"id":"g1","a":"t1","b":"t2","status":"active","phase":"ATTACKING",
                  "sidePhases":{"A":"ROLL","B":"PREPARING"},"sync":{},"lineups":{"B":[]}}}}
                """;
        GameStateRecord record = new GameStateRecord(1L, json, "admin");
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        ParallelTournamentService tournament = new ParallelTournamentService(states, users,
                mock(PerformanceRecordRepository.class), mock(GameControlRepository.class), mapper, events, 0, org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));
        PlayerActionService service = new PlayerActionService(states, users, mapper, events, online, tournament);

        when(online.isTeamReady("t2")).thenReturn(true);
        service.submit("parallel_b", "captain-command", List.of());

        var saved = mapper.readTree(record.getContent());
        assertThat(saved.at("/matches/g1/sidePhases/A").asText()).isEqualTo("ROLL");
        assertThat(saved.at("/matches/g1/sidePhases/B").asText()).isEqualTo("COUNTDOWN");
        assertThat(saved.at("/matches/g1/sync/commandedB").asBoolean()).isTrue();
        verify(online).startCountdown("t2");
    }

    @Test
    void afkPlayerCannotSubmitGameActionsUntilRestored() {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        UserAccount player = new UserAccount("afk_player", "挂机玩家", "销售部", "USER", "hash", "salt");
        player.assignTeam("t1");
        player.setAfk(true);
        when(users.findByUsername("afk_player")).thenReturn(Optional.of(player));

        PlayerActionService service = new PlayerActionService(states, users, new ObjectMapper(),
                mock(LobbyEventService.class), mock(OnlineGameService.class), mock(ParallelTournamentService.class));

        assertThatThrownBy(() -> service.submit("afk_player", "role-vote", List.of("u1")))
                .hasMessage("你当前处于挂机状态，请先取消挂机再操作");
        verify(states, never()).findLockedById(anyLong());
    }

    @Test
    void rejectsConcurrentAccumulationRollsFromTheSameTeam() throws Exception {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        LobbyEventService events = mock(LobbyEventService.class);
        OnlineGameService online = mock(OnlineGameService.class);
        ParallelTournamentService tournament = mock(ParallelTournamentService.class);
        ObjectMapper mapper = new ObjectMapper();
        UserAccount player = new UserAccount("roller", "掷骰玩家", "销售部", "USER", "hash", "salt");
        player.assignTeam("t1");
        GameStateRecord record = new GameStateRecord(1L,
                "{\"mode\":\"parallel\",\"stage\":\"ACCUMULATION\"}", "admin");
        when(users.findByUsername("roller")).thenReturn(Optional.of(player));
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));

        CountDownLatch firstRollEntered = new CountDownLatch(1);
        CountDownLatch finishFirstRoll = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstRollEntered.countDown();
            if (!finishFirstRoll.await(2, TimeUnit.SECONDS)) throw new AssertionError("first roll timed out");
            return null;
        }).when(tournament).beginAccumulation(any(), same(player));

        PlayerActionService service = new PlayerActionService(states, users, mapper, events, online, tournament);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.submit("roller", "accumulation-roll", List.of()));
            assertThat(firstRollEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.submit("roller", "accumulation-roll", List.of()));

            Throwable rejected = null;
            try { second.get(1, TimeUnit.SECONDS); }
            catch (ExecutionException error) { rejected = error.getCause(); }
            assertThat(rejected).isInstanceOf(IllegalStateException.class)
                    .hasMessage("队友正在掷积累骰，请等待本次结果");

            finishFirstRoll.countDown();
            first.get(1, TimeUnit.SECONDS);
        }
        verify(tournament, times(1)).beginAccumulation(any(), same(player));
        verify(states, times(1)).save(record);
    }

    @Test
    void teamPlayerSubmitsProphetWithoutHostEnteringTheSelection() throws Exception {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        LobbyEventService events = mock(LobbyEventService.class);
        OnlineGameService online = mock(OnlineGameService.class);
        ParallelTournamentService tournament = mock(ParallelTournamentService.class);
        ObjectMapper mapper = new ObjectMapper();
        UserAccount player = new UserAccount("player1", "队员", "技术部", "USER", "hash", "salt");
        player.assignTeam("t1");
        String json = """
                {"version":1,"teams":[
                  {"id":"t1","players":[]},
                  {"id":"t2","players":[
                    {"id":"q1","role":"front"},{"id":"q2","role":"front"},{"id":"q3","role":"front"},
                    {"id":"q4","role":"back"},{"id":"q5","role":"front"}]}
                ],"matches":{"m1":{"a":"t1","b":"t2"}},
                "live":{"matchId":"m1","step":"prophet","attacker":"A","prophet":{},"playerActions":{}}}
                """;
        GameStateRecord record = new GameStateRecord(1L, json, "admin");
        when(users.findByUsername("player1")).thenReturn(Optional.of(player));
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));

        new PlayerActionService(states, users, mapper, events, online, tournament)
                .submit("player1", "prophet", List.of("q1", "q2", "q3", "q4", "q5"));

        var saved = mapper.readTree(record.getContent());
        assertThat(saved.at("/live/prophet/A").size()).isEqualTo(5);
        assertThat(saved.at("/live/playerActions/prophetA").asBoolean()).isTrue();
        assertThat(saved.at("/live/step").asText()).isEqualTo("prophet");
        verify(states).save(record);
        verify(events).gameChanged();
    }

    @Test
    void individualRoleVoteOnlyRefreshesAdmin() {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        LobbyEventService events = mock(LobbyEventService.class);
        ParallelTournamentService tournament = mock(ParallelTournamentService.class);
        UserAccount player = new UserAccount("voter", "投票玩家", "销售部", "USER", "hash", "salt");
        player.assignTeam("t1");
        GameStateRecord record = new GameStateRecord(1L,
                "{\"mode\":\"parallel\",\"stage\":\"ROLE_VOTE\",\"teams\":[{\"id\":\"t1\",\"roleVoteStage\":\"captain\"}]}", "admin");
        when(users.findByUsername("voter")).thenReturn(Optional.of(player));
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));

        new PlayerActionService(states, users, new ObjectMapper(), events,
                mock(OnlineGameService.class), tournament)
                .submit("voter", "role-vote", List.of("u1"));

        verify(events).adminGameChanged();
        verify(events, never()).gameChanged();
    }

    @Test
    void advancedRoleVoteStageOnlyRefreshesItsTeam() {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        LobbyEventService events = mock(LobbyEventService.class);
        ParallelTournamentService tournament = mock(ParallelTournamentService.class);
        UserAccount player = new UserAccount("last_voter", "最后投票玩家", "销售部", "USER", "hash", "salt");
        player.assignTeam("t1");
        GameStateRecord record = new GameStateRecord(1L,
                "{\"mode\":\"parallel\",\"stage\":\"ROLE_VOTE\",\"teams\":[{\"id\":\"t1\",\"roleVoteStage\":\"captain\"}]}", "admin");
        when(users.findByUsername("last_voter")).thenReturn(Optional.of(player));
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        doAnswer(invocation -> {
            ObjectNode root = invocation.getArgument(0);
            ((ObjectNode) root.path("teams").get(0)).put("roleVoteStage", "strategist");
            return null;
        }).when(tournament).submitRoleVote(any(), same(player), anyList());

        new PlayerActionService(states, users, new ObjectMapper(), events,
                mock(OnlineGameService.class), tournament)
                .submit("last_voter", "role-vote", List.of("u1"));

        verify(events).teamGameChanged("t1");
        verify(events, never()).gameChanged();
        verify(events, never()).adminGameChanged();
    }

    @Test
    void completedAllRoleVotingRefreshesEveryoneForAccumulation() {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        LobbyEventService events = mock(LobbyEventService.class);
        ParallelTournamentService tournament = mock(ParallelTournamentService.class);
        UserAccount player = new UserAccount("final_voter", "最终投票玩家", "销售部", "USER", "hash", "salt");
        player.assignTeam("t1");
        GameStateRecord record = new GameStateRecord(1L,
                "{\"mode\":\"parallel\",\"stage\":\"ROLE_VOTE\",\"teams\":[{\"id\":\"t1\",\"roleVoteStage\":\"pitcher\"}]}", "admin");
        when(users.findByUsername("final_voter")).thenReturn(Optional.of(player));
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        doAnswer(invocation -> {
            ObjectNode root = invocation.getArgument(0);
            root.put("stage", "ACCUMULATION");
            ((ObjectNode) root.path("teams").get(0)).put("roleVoteStage", "complete");
            return null;
        }).when(tournament).submitRoleVote(any(), same(player), anyList());

        new PlayerActionService(states, users, new ObjectMapper(), events,
                mock(OnlineGameService.class), tournament)
                .submit("final_voter", "role-vote", List.of("u1"));

        verify(events).gameChanged();
        verify(events, never()).teamGameChanged(anyString());
        verify(events, never()).adminGameChanged();
    }
}
