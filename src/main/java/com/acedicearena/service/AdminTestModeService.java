package com.acedicearena.service;

import com.acedicearena.domain.GameControl;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameControlRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class AdminTestModeService {
    public static final String USERNAME_PREFIX = "__arena_test_";
    private final boolean enabled;
    private final UserAccountRepository users;
    private final GameControlRepository controls;
    private final GameStateRepository states;
    private final LobbyService lobby;
    private final ParallelTournamentService tournament;
    private final LobbyEventService events;
    private final ObjectMapper mapper;

    public AdminTestModeService(@Value("${app.test-mode.enabled:false}") boolean enabled,
                                UserAccountRepository users, GameControlRepository controls,
                                GameStateRepository states, LobbyService lobby,
                                ParallelTournamentService tournament, LobbyEventService events,
                                ObjectMapper mapper) {
        this.enabled = enabled; this.users = users; this.controls = controls; this.states = states;
        this.lobby = lobby; this.tournament = tournament; this.events = events; this.mapper = mapper;
    }

    @Transactional
    public TestStatus prepare() {
        requireEnabled();
        List<UserAccount> all = users.findAll();
        users.deleteAll(all.stream().filter(AdminTestModeService::isTestUser).toList());
        users.flush();
        all.stream().filter(user -> "USER".equals(user.getRole()) && !isTestUser(user))
                .forEach(user -> user.assignTeam(null));
        List<UserAccount> testUsers = new ArrayList<>();
        for (int index = 0; index < LobbyService.PARTICIPANT_COUNT; index++) {
            int teamIndex = index / LobbyService.TEAM_SIZE;
            int seat = index % LobbyService.TEAM_SIZE;
            boolean frontEnd = seat < 15;
            UserAccount user = new UserAccount(USERNAME_PREFIX + String.format("%03d", index + 1),
                    "沙盘队员" + String.format("%03d", index + 1), frontEnd ? "销售部" : "技术部",
                    "USER", "test-mode", "00");
            user.setPerformance(frontEnd, frontEnd ? BigDecimal.valueOf(100_000L - index * 137L) : BigDecimal.ZERO);
            user.assignTeam(LobbyService.TEAM_IDS.get(teamIndex)); user.setReady(true); testUsers.add(user);
        }
        users.saveAll(testUsers);
        users.flush();
        control().changePhase("GROUPED");
        lobby.start();
        return status();
    }

    @Transactional
    public TestStatus advance(String username) {
        requireEnabled();
        if (!status().sandboxPlayers().isEmpty()) throw new IllegalStateException("双人沙盘模式下请由指定玩家推进比赛");
        tournament.simulateStep(username);
        return status();
    }

    @Transactional
    public TestStatus assignSandboxPlayers(String firstUsername, String firstTeamId,
                                           String secondUsername, String secondTeamId) {
        return assignSandboxPlayers(firstUsername, firstTeamId, inferredIdentity(firstUsername),
                secondUsername, secondTeamId, inferredIdentity(secondUsername));
    }

    private String inferredIdentity(String username) {
        UserAccount user = realPlayer(username);
        return user.isFrontEnd() ? "front" : "back";
    }

    @Transactional
    public TestStatus assignSandboxPlayers(String firstUsername, String firstTeamId, String firstIdentity,
                                           String secondUsername, String secondTeamId, String secondIdentity) {
        requireEnabled();
        if (firstUsername == null || firstUsername.equals(secondUsername)) throw new IllegalArgumentException("请选择两名不同的真实玩家");
        if (!LobbyService.TEAM_IDS.contains(firstTeamId) || !LobbyService.TEAM_IDS.contains(secondTeamId))
            throw new IllegalArgumentException("请选择有效队伍");
        int firstIndex = LobbyService.TEAM_IDS.indexOf(firstTeamId), secondIndex = LobbyService.TEAM_IDS.indexOf(secondTeamId);
        if (!firstTeamId.equals(secondTeamId) && firstIndex / 2 != secondIndex / 2)
            throw new IllegalArgumentException("两名玩家只能加入同一队伍，或加入同一对战组中的相对队伍");
        validateIdentity(firstIdentity); validateIdentity(secondIdentity);
        TestStatus current = status();
        if (!current.active()) throw new IllegalStateException("请先建立管理员沙盘");
        if (!current.sandboxPlayers().isEmpty()) throw new IllegalStateException("本次沙盘已经指定正式玩家，请清理后重新建立以更换");
        UserAccount first = realPlayer(firstUsername), second = realPlayer(secondUsername);
        List<UserAccount> replacements = users.findAll().stream().filter(AdminTestModeService::isTestUser)
                .filter(user -> firstTeamId.equals(user.getTeamId()) || secondTeamId.equals(user.getTeamId())).toList();
        UserAccount firstReplaced = replacements.stream().filter(user -> firstTeamId.equals(user.getTeamId())
                        && identityMatches(user, firstIdentity)).findFirst()
                .orElseThrow(() -> new IllegalStateException("第一支目标队伍没有可替换的沙盘队员"));
        UserAccount secondReplaced = replacements.stream().filter(user -> secondTeamId.equals(user.getTeamId())
                        && !user.getId().equals(firstReplaced.getId()) && identityMatches(user, secondIdentity)).findFirst()
                .orElseThrow(() -> new IllegalStateException("第二支目标队伍没有可替换的沙盘队员"));
        firstReplaced.assignTeam(null); secondReplaced.assignTeam(null);
        first.assignTeam(firstTeamId); first.setReady(true);
        second.assignTeam(secondTeamId); second.setReady(true);
        users.saveAll(List.of(firstReplaced, secondReplaced, first, second)); users.flush();
        tournament.configureSandboxPlayers(List.of(
                new ParallelTournamentService.SandboxAssignment(first, firstReplaced, firstTeamId, firstIdentity),
                new ParallelTournamentService.SandboxAssignment(second, secondReplaced, secondTeamId, secondIdentity)));
        return status();
    }

    private void validateIdentity(String identity) {
        if (!("front".equals(identity) || "back".equals(identity))) throw new IllegalArgumentException("沙盘身份只能选择前端或后端");
    }

    private boolean identityMatches(UserAccount user, String identity) {
        return "front".equals(identity) == user.isFrontEnd();
    }

    private UserAccount realPlayer(String username) {
        return users.findByUsername(username).filter(user -> "USER".equals(user.getRole()) && !isTestUser(user))
                .orElseThrow(() -> new IllegalArgumentException("真实玩家不存在：" + username));
    }

    @Transactional(readOnly = true)
    public List<SoloCandidate> soloCandidates() {
        if (!enabled) return List.of();
        return users.findAll().stream().filter(user -> "USER".equals(user.getRole()) && !isTestUser(user))
                .filter(user -> AccountService.hasUsableDepartment(user.getDepartment()))
                .sorted(java.util.Comparator.comparing(UserAccount::getDisplayName))
                .map(user -> new SoloCandidate(user.getUsername(), user.getDisplayName(), user.getDepartment()))
                .toList();
    }

    @Transactional(readOnly = true)
    public LobbyService.LobbyView playerView(String teamId) {
        requireEnabled();
        if (!LobbyService.TEAM_IDS.contains(teamId)) throw new IllegalArgumentException("请选择有效队伍");
        TestStatus current = status();
        var configured = current.sandboxPlayers().stream().filter(player -> teamId.equals(player.teamId())).findFirst();
        if (configured.isPresent()) {
            return lobby.sandboxPlayerView(configured.get().username());
        }
        UserAccount player = users.findAll().stream()
                .filter(AdminTestModeService::isTestUser)
                .filter(user -> teamId.equals(user.getTeamId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("请先建立管理员沙盘"));
        return lobby.sandboxPlayerView(player.getUsername());
    }

    @Transactional
    public TestStatus cleanup() {
        requireEnabled();
        states.findById(1L).ifPresent(record -> {
            try {
                var root = mapper.readTree(record.getContent());
                root.path("sandboxPlayers").forEach(player -> users.findByUsername(player.path("username").asText())
                        .ifPresent(user -> user.assignTeam(null)));
                String legacyUsername = root.at("/sandboxSolo/username").asText(null);
                if (legacyUsername != null) users.findByUsername(legacyUsername).ifPresent(user -> user.assignTeam(null));
            } catch (Exception ignored) { }
        });
        users.deleteAll(users.findAll().stream().filter(AdminTestModeService::isTestUser).toList());
        users.flush();
        if (states.existsById(1L)) states.deleteById(1L);
        control().changePhase("PREPARING"); events.stateChanged();
        return status();
    }

    @Transactional(readOnly = true)
    public TestStatus status() {
        int count = (int) users.findAll().stream().filter(AdminTestModeService::isTestUser).count();
        String phase = controls.findById(1L).map(GameControl::getPhase).orElse("PREPARING");
        String champion = null;
        List<SandboxPlayerStatus> sandboxPlayers = new ArrayList<>();
        var state = states.findById(1L).orElse(null);
        if (state != null) try {
            var root = mapper.readTree(state.getContent());
            champion = root.path("champion").asText(null);
            root.path("sandboxPlayers").forEach(player -> sandboxPlayers.add(new SandboxPlayerStatus(
                    player.path("username").asText(), player.path("displayName").asText(), player.path("teamId").asText(),
                    player.path("identity").asText("front"))));
            if (sandboxPlayers.isEmpty() && root.path("sandboxSolo").hasNonNull("username")) sandboxPlayers.add(new SandboxPlayerStatus(
                root.at("/sandboxSolo/username").asText(), root.at("/sandboxSolo/displayName").asText(), root.at("/sandboxSolo/teamId").asText(), "front"));
        } catch (Exception ignored) { }
        return new TestStatus(enabled, count > 0, phase, count, champion, sandboxPlayers);
    }

    private GameControl control() { return controls.findById(1L).orElseGet(() -> controls.save(new GameControl(1L))); }
    public static boolean isTestUser(UserAccount user) { return user.getUsername().startsWith(USERNAME_PREFIX); }
    private void requireEnabled() { if (!enabled) throw new IllegalStateException("管理员测试模式未启用"); }

    public record TestStatus(boolean enabled, boolean active, String phase, int testUsers, String champion,
                             List<SandboxPlayerStatus> sandboxPlayers) {}
    public record SandboxPlayerStatus(String username, String displayName, String teamId, String identity) {}
    public record SoloCandidate(String username, String displayName, String department) {}
}
