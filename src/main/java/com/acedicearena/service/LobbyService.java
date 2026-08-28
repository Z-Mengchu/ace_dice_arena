package com.acedicearena.service;

import com.acedicearena.domain.GameControl;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameControlRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class LobbyService {
    public static final String STAND_IN_PREFIX = "__arena_stand_in_";
    public static final int TEAM_SIZE = 30;
    public static final int PARTICIPANT_COUNT = TEAM_SIZE * 8;
    public static final List<String> TEAM_IDS = List.of("t1","t2","t3","t4","t5","t6","t7","t8");
    public static final List<String> TEAM_NAMES = List.of("雷霆战区","烈焰战区","飓风战区","磐石战区","星驰战区","锋芒战区","凌云战区","破晓战区");
    private final UserAccountRepository users;
    private final GameControlRepository controls;
    private final GameStateRepository gameStates;
    private final LobbyEventService events;
    private final ObjectMapper objectMapper;
    private final ParallelTournamentService tournament;
    private final long rosterCacheTtlMs;
    private final Object rosterCacheLock = new Object();
    private volatile RosterCache rosterCache;

    public LobbyService(UserAccountRepository users, GameControlRepository controls,
                        GameStateRepository gameStates, LobbyEventService events, ObjectMapper objectMapper,
                        ParallelTournamentService tournament,
                        @Value("${app.cache.roster-ttl-ms:300}") long rosterCacheTtlMs) {
        this.users = users; this.controls = controls; this.gameStates = gameStates;
        this.events = events; this.objectMapper = objectMapper; this.tournament = tournament;
        this.rosterCacheTtlMs = Math.max(0, rosterCacheTtlMs);
    }

    @Transactional
    public LobbyView view(String username) { return buildView(username, false); }

    @Transactional
    public LobbyView sandboxPlayerView(String username) { return buildView(username, true); }

    private LobbyView buildView(String username, boolean sandboxPreview) {
        UserAccount me = users.findByUsername(username).orElseThrow();
        GameControl control = control();
        JsonNode currentGame = savedGame();
        boolean sandboxPlayer = username.equals(currentGame.at("/sandboxSolo/username").asText(null));
        for (JsonNode player : currentGame.path("sandboxPlayers")) {
            if (username.equals(player.path("username").asText())) sandboxPlayer = true;
        }
        List<UserView> playerAccounts = playerRoster();
        boolean privateSandbox = !sandboxPreview && !sandboxPlayer && !"ADMIN".equals(me.getRole())
                && playerAccounts.stream().anyMatch(user -> user.username().startsWith(AdminTestModeService.USERNAME_PREFIX));
        List<UserView> all = playerAccounts.stream()
                .filter(user -> !privateSandbox || !user.username().startsWith(AdminTestModeService.USERNAME_PREFIX)).toList();
        List<TeamView> teams = new ArrayList<>();
        for (int i = 0; i < TEAM_IDS.size(); i++) {
            String tid = TEAM_IDS.get(i);
            List<UserView> members = all.stream().filter(u -> tid.equals(u.teamId())).toList();
            teams.add(new TeamView(tid, TEAM_NAMES.get(i), members, (int) members.stream().filter(UserView::ready).count()));
        }
        List<UserView> participants = all.stream().filter(u -> u.teamId() != null).toList();
        boolean allReady = participants.size() == PARTICIPANT_COUNT
                && teams.stream().allMatch(t -> t.members().size() == TEAM_SIZE)
                && participants.stream().allMatch(UserView::ready);
        JsonNode savedGame = privateSandbox ? objectMapper.createObjectNode() : currentGame;
        List<MatchView> matches = List.of(
                match(1, teams.get(0), teams.get(1), savedGame), match(2, teams.get(2), teams.get(3), savedGame),
                match(3, teams.get(4), teams.get(5), savedGame), match(4, teams.get(6), teams.get(7), savedGame));
        List<UserView> spectators = all.stream().filter(u -> u.teamId() == null).toList();
        teams.add(new TeamView("spectator", "观战席 / 未分组", spectators,
                (int) spectators.stream().filter(UserView::ready).count()));
        boolean fullRoster = sandboxPreview || "ADMIN".equals(me.getRole());
        String opponentTeamId = opponentTeamId(me.getTeamId(), savedGame);
        List<TeamView> visibleTeams = fullRoster ? teams : teams.stream().map(team ->
                team.id().equals(me.getTeamId()) || team.id().equals(opponentTeamId)
                        ? team
                        : new TeamView(team.id(), team.name(), List.of(), team.readyCount())).toList();
        boolean canReady = !privateSandbox && me.getTeamId() != null && !"PLAYING".equals(control.getPhase());
        return new LobbyView(privateSandbox ? "PREPARING" : control.getPhase(), userView(me), canReady,
                visibleTeams, matches, allReady);
    }

    private String opponentTeamId(String teamId, JsonNode game) {
        if (teamId == null) return null;
        String latest = null;
        JsonNode gameMatches = game.path("matches");
        if (gameMatches.isObject()) {
            Iterator<JsonNode> iterator = gameMatches.elements();
            while (iterator.hasNext()) {
                JsonNode match = iterator.next();
                String a = match.path("a").asText(), b = match.path("b").asText();
                if (!teamId.equals(a) && !teamId.equals(b)) continue;
                String opponent = teamId.equals(a) ? b : a;
                if ("active".equals(match.path("status").asText())) return opponent;
                latest = opponent;
            }
        }
        if (latest != null) return latest;
        int index = TEAM_IDS.indexOf(teamId);
        return index < 0 ? null : TEAM_IDS.get(index % 2 == 0 ? index + 1 : index - 1);
    }

    @Transactional
    public void assign(long userId, String teamId) {
        if (teamId != null && !teamId.isBlank() && !TEAM_IDS.contains(teamId)) throw new IllegalArgumentException("invalid team");
        UserAccount user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (!"USER".equals(user.getRole())) throw new IllegalArgumentException("管理员不参加分组");
        if (!AccountService.hasUsableDepartment(user.getDepartment())) throw new IllegalArgumentException("未配置部门的用户不能参加分组");
        String targetTeam = teamId == null || teamId.isBlank() ? null : teamId;
        if (targetTeam != null && !targetTeam.equals(user.getTeamId())) {
            long memberCount = users.findAll().stream().filter(u -> targetTeam.equals(u.getTeamId())).count();
            if (memberCount >= TEAM_SIZE) throw new IllegalStateException("该队伍已经达到 " + TEAM_SIZE + " 人上限");
        }
        user.assignTeam(targetTeam);
        users.save(user);
        control().changePhase("GROUPED");
        invalidateRoster();
        events.stateChanged();
    }

    @Transactional
    public UserView replaceWithStandIn(long userId) {
        if (!"GROUPED".equals(control().getPhase()))
            throw new IllegalStateException("只能在分组完成后的准备阶段设置沙盘队友");
        UserAccount original = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!"USER".equals(original.getRole()) || original.getTeamId() == null || isStandIn(original))
            throw new IllegalArgumentException("只能替换已分组的正式玩家");
        String teamId = original.getTeamId();
        String username = STAND_IN_PREFIX + original.getId();
        String displayName = original.getDisplayName();
        UserAccount standIn = users.findByUsername(username).orElseGet(() ->
                new UserAccount(username, displayName, original.getDepartment(), "USER", "disabled", "00"));
        standIn.syncProfile(displayName, original.getDepartment());
        standIn.setPerformance(original.isFrontEnd(), original.getGmv());
        original.assignTeam(null);
        standIn.assignTeam(teamId);
        standIn.setReady(true);
        users.save(original);
        standIn = users.save(standIn);
        invalidateRoster();
        events.stateChanged();
        return userView(standIn);
    }

    @Transactional
    public UserView restoreFromStandIn(long standInUserId) {
        if (!"GROUPED".equals(control().getPhase()))
            throw new IllegalStateException("只能在分组完成后的准备阶段恢复真实队友");
        UserAccount standIn = users.findById(standInUserId)
                .orElseThrow(() -> new IllegalArgumentException("沙盘队友不存在"));
        if (!isStandIn(standIn) || standIn.getTeamId() == null)
            throw new IllegalArgumentException("该席位不是有效的沙盘队友");
        long originalUserId = originalUserId(standIn)
                .orElseThrow(() -> new IllegalStateException("未找到沙盘队友对应的真实玩家"));
        UserAccount original = users.findById(originalUserId)
                .orElseThrow(() -> new IllegalStateException("未找到沙盘队友对应的真实玩家"));
        String teamId = standIn.getTeamId();
        users.delete(standIn);
        original.assignTeam(teamId);
        original.setReady(false);
        original = users.save(original);
        invalidateRoster();
        events.stateChanged();
        return userView(original);
    }

    @Transactional
    public void ready(String username, boolean ready) {
        UserAccount user = users.findByUsername(username).orElseThrow();
        if (user.getTeamId() == null) throw new IllegalStateException("你不在本轮分组中");
        if ("PLAYING".equals(control().getPhase())) throw new IllegalStateException("比赛进行中不能修改准备状态");
        user.setReady(ready);
        if (ready) user.setAfk(false);
        users.save(user); invalidateRoster(); events.stateChanged();
    }

    @Transactional
    public UserView cancelAfk(String username) {
        UserAccount user = users.findByUsername(username).orElseThrow();
        if (user.getTeamId() == null) throw new IllegalStateException("你不在本轮分组中");
        if (!user.isAfk()) return userView(user);
        user.setAfk(false);
        users.save(user);
        tournament.restoreAfkPlayer(user, username);
        invalidateRoster();
        events.stateChanged();
        return userView(user);
    }

    @Transactional
    public void start() {
        List<UserAccount> participants = users.findAll().stream()
                .filter(u -> "USER".equals(u.getRole()) && AccountService.hasUsableDepartment(u.getDepartment())
                        && u.getTeamId() != null).toList();
        boolean ready = participants.size() == PARTICIPANT_COUNT
                && TEAM_IDS.stream().allMatch(teamId -> participants.stream()
                    .filter(u -> teamId.equals(u.getTeamId())).count() == TEAM_SIZE)
                && participants.stream().allMatch(UserAccount::isReady);
        if (!ready) throw new IllegalStateException("需要 8 队各 " + TEAM_SIZE + " 人且 "
                + PARTICIPANT_COUNT + " 名参赛用户全部准备");
        control().changePhase("PLAYING");
        tournament.start("system");
        events.stateChanged();
    }

    @Transactional
    public void resetReady(boolean regroup) {
        List<UserAccount> allPlayers = users.findAll().stream()
                .filter(u -> "USER".equals(u.getRole())).toList();
        List<UserAccount> standIns = allPlayers.stream().filter(LobbyService::isStandIn).toList();
        List<UserAccount> players = allPlayers.stream().filter(user -> !isStandIn(user)).toList();
        players.forEach(user -> {
            user.setReady(false);
            user.setAfk(false);
            if (regroup) user.assignTeam(null);
        });
        users.saveAll(players);
        if (regroup) users.deleteAll(standIns);
        else {
            standIns.forEach(user -> user.setReady(true));
            users.saveAll(standIns);
        }
        control().changePhase(regroup ? "PREPARING" : "GROUPED");
        invalidateRoster();
        events.stateChanged();
    }

    @Transactional
    public void readyAll(boolean markAfk) {
        if ("PLAYING".equals(control().getPhase())) throw new IllegalStateException("比赛进行中不能修改准备状态");
        List<UserAccount> participants = users.findAll().stream()
                .filter(user -> "USER".equals(user.getRole()) && AccountService.hasUsableDepartment(user.getDepartment())
                        && user.getTeamId() != null).toList();
        if (participants.isEmpty()) throw new IllegalStateException("当前没有已分组玩家");
        participants.forEach(user -> {
            if (markAfk && !user.isReady() && !isStandIn(user)) user.setAfk(true);
            user.setReady(true);
        });
        users.saveAll(participants);
        control().changePhase("GROUPED");
        invalidateRoster();
        events.stateChanged();
    }

    public void readyAll() { readyAll(true); }

    @Transactional
    public void resetTwoDayTournament() { tournament.resetTwoDayTournament(); }

    public UserAccount requireUser(String username) { return users.findByUsername(username).orElseThrow(); }
    private GameControl control() { return controls.findById(1L).orElseGet(() -> controls.save(new GameControl(1L))); }
    public static boolean isStandIn(UserAccount user) { return user != null && user.getUsername().startsWith(STAND_IN_PREFIX); }
    private static Optional<Long> originalUserId(UserAccount user) {
        if (!isStandIn(user)) return Optional.empty();
        try { return Optional.of(Long.parseLong(user.getUsername().substring(STAND_IN_PREFIX.length()))); }
        catch (NumberFormatException ignored) { return Optional.empty(); }
    }
    private UserView userView(UserAccount u) {
        Optional<Long> originalId = originalUserId(u);
        String originalName = originalId.flatMap(users::findById).map(UserAccount::getDisplayName).orElse(null);
        return new UserView(u.getId(), u.getUsername(), originalName == null ? u.getDisplayName() : originalName,
                u.getDepartment(), u.getRole(), u.getTeamId(), u.isReady(), u.isAfk(), u.isFrontEnd(), u.getGmv(),
                isStandIn(u), originalId.orElse(null), originalName);
    }
    private JsonNode savedGame() {
        return gameStates.findById(1L).map(record -> {
            try { return objectMapper.readTree(record.getContent()); }
            catch (Exception ignored) { return objectMapper.createObjectNode(); }
        }).orElseGet(objectMapper::createObjectNode);
    }

    private List<UserView> playerRoster() {
        long now = System.currentTimeMillis();
        RosterCache cached = rosterCache;
        if (rosterCacheTtlMs > 0 && cached != null && now - cached.loadedAt() < rosterCacheTtlMs) return cached.users();
        synchronized (rosterCacheLock) {
            cached = rosterCache;
            now = System.currentTimeMillis();
            if (rosterCacheTtlMs > 0 && cached != null && now - cached.loadedAt() < rosterCacheTtlMs) return cached.users();
            List<UserView> loaded = users.findAll().stream()
                    .filter(user -> "USER".equals(user.getRole()) && AccountService.hasUsableDepartment(user.getDepartment()))
                    .map(this::userView).toList();
            rosterCache = new RosterCache(now, loaded);
            return loaded;
        }
    }

    private void invalidateRoster() { rosterCache = null; }

    private MatchView match(int no, TeamView a, TeamView b, JsonNode savedGame) {
        int scoreA = 0, scoreB = 0;
        JsonNode matches = savedGame.path("matches");
        if (matches.isObject()) {
            Iterator<JsonNode> records = matches.elements();
            while (records.hasNext()) {
                JsonNode record = records.next();
                String left = record.path("a").asText();
                String right = record.path("b").asText();
                if (a.id().equals(left) && b.id().equals(right)) {
                    scoreA = record.path("winsA").asInt(); scoreB = record.path("winsB").asInt();
                } else if (a.id().equals(right) && b.id().equals(left)) {
                    scoreA = record.path("winsB").asInt(); scoreB = record.path("winsA").asInt();
                }
            }
        }
        return new MatchView(no, a.id(), a.name(), b.id(), b.name(), scoreA, scoreB);
    }

    public record UserView(Long id, String username, String displayName, String department, String role,
                           String teamId, boolean ready, boolean afk, boolean frontEnd, java.math.BigDecimal gmv, boolean standIn,
                           Long originalUserId, String originalDisplayName) {}
    public record TeamView(String id, String name, List<UserView> members, int readyCount) {}
    public record MatchView(int number, String teamA, String nameA, String teamB, String nameB, int scoreA, int scoreB) {}
    public record LobbyView(String phase, UserView me, boolean canReady, List<TeamView> teams,
                            List<MatchView> matches, boolean allReady) {}
    private record RosterCache(long loadedAt, List<UserView> users) {}
}
