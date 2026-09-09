package com.acedicearena;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.LobbyEventService;
import com.acedicearena.service.PlayerActionService;
import com.acedicearena.service.ParallelTournamentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlayerActionServiceTest {

    @Test
    void afkPlayerCannotSubmitGameActionsUntilRestored() {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        UserAccount player = new UserAccount("afk_player", "挂机玩家", "销售部", "USER", "hash", "salt");
        player.assignTeam("t1");
        player.setAfk(true);
        when(users.findByUsername("afk_player")).thenReturn(Optional.of(player));

        PlayerActionService service = new PlayerActionService(states, users, new ObjectMapper(),
                mock(LobbyEventService.class), mock(ParallelTournamentService.class));

        assertThatThrownBy(() -> service.submit("afk_player", "role-vote", List.of("u1")))
                .hasMessage("你当前处于挂机状态，请先取消挂机再操作");
        verify(states, never()).findLockedById(anyLong());
    }

    @Test
    void squadFormRerollSquadOrderRoundGuessDispatchAndBlindBoxOpensUnderStateLock() {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        LobbyEventService events = mock(LobbyEventService.class);
        ParallelTournamentService tournament = mock(ParallelTournamentService.class);
        ObjectMapper mapper = new ObjectMapper();
        UserAccount player = new UserAccount("player", "队员", "技术部", "USER", "hash", "salt");
        player.assignTeam("t1");
        when(users.findByUsername("player")).thenReturn(Optional.of(player));
        GameStateRecord record = new GameStateRecord(1L,
                "{\"mode\":\"parallel\",\"stage\":\"TACTICS\",\"teams\":[{\"id\":\"t1\"}]}", "admin");
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        PlayerActionService service = new PlayerActionService(states, users, mapper, events, tournament);

        service.submit("player", "squad-form", List.of("u1"));
        when(tournament.openBlindBoxLocked(any(), any()))
                .thenReturn(new ParallelTournamentService.BlindBoxResult(2, new int[]{2, -1, 3}, 0));
        service.submit("player", "blind-box-open", List.of());
        service.submit("player", "reroll", List.of("u5"));
        service.submit("player", "squad-order", List.of("3", "1", "2", "4", "5", "6"));
        service.submit("player", "round-guess", List.of("u101"));

        verify(tournament).dispatchPlayerAction(any(), eq(player), eq("squad-form"), eq(List.of("u1")));
        verify(tournament).dispatchPlayerAction(any(), eq(player), eq("reroll"), eq(List.of("u5")));
        verify(tournament).dispatchPlayerAction(any(), eq(player), eq("squad-order"), eq(List.of("3", "1", "2", "4", "5", "6")));
        verify(tournament).dispatchPlayerAction(any(), eq(player), eq("round-guess"), eq(List.of("u101")));
        // 开盲盒由赛事服务在当前事务中先锁状态再写独立行，不走动作分发表；未带序号时为 null
        verify(tournament).openBlindBoxLocked(eq(player), isNull());
        verify(tournament, never()).dispatchPlayerAction(any(), any(), eq("blind-box-open"), any());
        verify(tournament, never()).submitSandboxAction(any(), any(), any(), any());
        verify(states, times(4)).save(record);
        verify(events, times(5)).gameChanged();
    }

    @Test
    void removedLegacyActionsAreRejectedInParallelMode() {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ParallelTournamentService tournament = new ParallelTournamentService(states, users,
                mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                mock(LobbyEventService.class), 0,
                mock(com.acedicearena.repository.BattleReportRepository.class),
                mock(com.acedicearena.repository.MatchReportRepository.class),
                mock(com.acedicearena.repository.PlayerBlindBoxRepository.class));
        UserAccount player = new UserAccount("player", "队员", "技术部", "USER", "hash", "salt");
        player.assignTeam("t1");
        when(users.findByUsername("player")).thenReturn(Optional.of(player));
        GameStateRecord record = new GameStateRecord(1L,
                "{\"mode\":\"parallel\",\"stage\":\"BATTLE\",\"teams\":[{\"id\":\"t1\"}]}", "admin");
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        PlayerActionService service = new PlayerActionService(states, users, mapper,
                mock(LobbyEventService.class), tournament);

        for (String type : List.of("accumulation-roll", "prophet", "lineup", "pitcher-roll",
                "attack-boost", "captain-command", "sandbox-ready", "sandbox-roll")) {
            assertThatThrownBy(() -> service.submit("player", type, List.of()))
                    .as("旧动作 %s 应被拒绝", type)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("未知的玩家操作");
        }
        verify(states, never()).save(any());
    }

    @Test
    void sandboxPlayersGoThroughTheSandboxDispatch() {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        ParallelTournamentService tournament = mock(ParallelTournamentService.class);
        ObjectMapper mapper = new ObjectMapper();
        UserAccount player = new UserAccount("sandbox_player", "沙盘玩家", "业务部", "USER", "hash", "salt");
        player.assignTeam("t1");
        when(users.findByUsername("sandbox_player")).thenReturn(Optional.of(player));
        GameStateRecord record = new GameStateRecord(1L,
                "{\"mode\":\"parallel\",\"stage\":\"CAPTAIN_VOTE\",\"teams\":[{\"id\":\"t1\"}],"
                        + "\"sandboxPlayers\":[{\"username\":\"sandbox_player\"}]}", "admin");
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        when(tournament.isSandboxPlayer(any(), eq("sandbox_player"))).thenReturn(true);
        PlayerActionService service = new PlayerActionService(states, users, mapper,
                mock(LobbyEventService.class), tournament);

        service.submit("sandbox_player", "role-vote", List.of("u1"));

        verify(tournament).submitSandboxAction(any(), eq(player), eq("role-vote"), eq(List.of("u1")));
        verify(tournament, never()).submitRoleVote(any(), any(), any());
        verify(states).save(record);
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
                "{\"mode\":\"parallel\",\"stage\":\"CAPTAIN_VOTE\",\"teams\":[{\"id\":\"t1\",\"roles\":{}}]}", "admin");
        when(users.findByUsername("voter")).thenReturn(Optional.of(player));
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));

        new PlayerActionService(states, users, new ObjectMapper(), events, tournament)
                .submit("voter", "role-vote", List.of("u1"));

        verify(events).adminGameChanged();
        verify(events, never()).gameChanged();
        verify(events, never()).teamGameChanged(anyString());
    }

    @Test
    void roleVoteThatElectsTheCaptainOnlyRefreshesItsTeam() {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        LobbyEventService events = mock(LobbyEventService.class);
        ParallelTournamentService tournament = mock(ParallelTournamentService.class);
        UserAccount player = new UserAccount("last_voter", "最后投票玩家", "销售部", "USER", "hash", "salt");
        player.assignTeam("t1");
        GameStateRecord record = new GameStateRecord(1L,
                "{\"mode\":\"parallel\",\"stage\":\"CAPTAIN_VOTE\",\"teams\":[{\"id\":\"t1\",\"roles\":{}}]}", "admin");
        when(users.findByUsername("last_voter")).thenReturn(Optional.of(player));
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        doAnswer(invocation -> {
            ObjectNode root = invocation.getArgument(0);
            ((ObjectNode) root.path("teams").get(0)).withObject("/roles").put("captain", "u1");
            return null;
        }).when(tournament).dispatchPlayerAction(any(), same(player), eq("role-vote"), anyList());

        new PlayerActionService(states, users, new ObjectMapper(), events, tournament)
                .submit("last_voter", "role-vote", List.of("u1"));

        verify(events).teamGameChanged("t1");
        verify(events, never()).gameChanged();
        verify(events, never()).adminGameChanged();
    }

    @Test
    void roleVoteThatCompletesAllVotingRefreshesEveryoneForSquadForm() {
        GameStateRepository states = mock(GameStateRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        LobbyEventService events = mock(LobbyEventService.class);
        ParallelTournamentService tournament = mock(ParallelTournamentService.class);
        UserAccount player = new UserAccount("final_voter", "最终投票玩家", "销售部", "USER", "hash", "salt");
        player.assignTeam("t1");
        GameStateRecord record = new GameStateRecord(1L,
                "{\"mode\":\"parallel\",\"stage\":\"CAPTAIN_VOTE\",\"teams\":[{\"id\":\"t1\",\"roles\":{}}]}", "admin");
        when(users.findByUsername("final_voter")).thenReturn(Optional.of(player));
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        doAnswer(invocation -> {
            ObjectNode root = invocation.getArgument(0);
            root.put("stage", "SQUAD_FORM");
            ((ObjectNode) root.path("teams").get(0)).withObject("/roles").put("captain", "u1");
            return null;
        }).when(tournament).dispatchPlayerAction(any(), same(player), eq("role-vote"), anyList());

        new PlayerActionService(states, users, new ObjectMapper(), events, tournament)
                .submit("final_voter", "role-vote", List.of("u1"));

        verify(events).gameChangedNow();
        verify(events, never()).gameChanged();
        verify(events, never()).teamGameChanged(anyString());
        verify(events, never()).adminGameChanged();
    }
}
