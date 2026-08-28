package com.acedicearena.web;

import com.acedicearena.domain.UserAccount;
import com.acedicearena.service.AdminTestModeService;
import com.acedicearena.service.LobbyEventService;
import com.acedicearena.service.LobbyService;
import com.acedicearena.service.PlayerActionService;
import com.acedicearena.service.PerformanceImportService;
import com.acedicearena.service.ParallelTournamentService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LobbyController {
    private final LobbyService lobby;
    private final LobbyEventService events;
    private final PlayerActionService playerActions;
    private final PerformanceImportService performance;
    private final AdminTestModeService testMode;
    private final ParallelTournamentService tournament;
    public LobbyController(LobbyService lobby, LobbyEventService events, PlayerActionService playerActions,
                           PerformanceImportService performance, AdminTestModeService testMode,
                           ParallelTournamentService tournament) {
        this.lobby = lobby; this.events = events; this.playerActions = playerActions;
        this.performance = performance; this.testMode = testMode; this.tournament = tournament;
    }

    @GetMapping("/lobby")
    public LobbyService.LobbyView lobby(HttpSession s) { return lobby.view(user(s)); }

    @PostMapping("/lobby/ready")
    public ResponseEntity<?> ready(@RequestBody ReadyBody body, HttpSession s) {
        try { lobby.ready(user(s), body.ready()); return ResponseEntity.ok(Map.of("ok", true)); }
        catch (IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/lobby/afk/cancel")
    public ResponseEntity<?> cancelAfk(HttpSession s) {
        try { return ResponseEntity.ok(lobby.cancelAfk(user(s))); }
        catch (IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error", e.getMessage())); }
    }

    @GetMapping("/lobby/events")
    public SseEmitter events(HttpSession s) {
        UserAccount u = lobby.requireUser(user(s));
        return events.subscribe(u.getUsername(), u.getTeamId(), u.getRole());
    }

    @PostMapping("/lobby/chat")
    public ResponseEntity<?> chat(@RequestBody ChatBody body, HttpSession s) {
        UserAccount u = lobby.requireUser(user(s));
        if (u.getTeamId() == null) return ResponseEntity.status(409).body(Map.of("error", "观战用户不能发送队伍消息"));
        String content = body.content() == null ? "" : body.content().trim();
        if (content.isEmpty() || content.length() > 300) return ResponseEntity.badRequest().body(Map.of("error", "消息长度需为 1-300 字"));
        events.chat(u.getTeamId(), u.getDisplayName(), content);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/lobby/player-action")
    public ResponseEntity<?> playerAction(@RequestBody PlayerActionBody body, HttpSession s) {
        try {
            playerActions.submit(user(s), body.type(), body.selections());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/admin/dashboard")
    public ResponseEntity<?> admin(HttpSession s) { return adminOnly(s, () -> lobby.view(user(s))); }

    @PutMapping("/admin/users/{id}/team")
    public ResponseEntity<?> assign(@PathVariable long id, @RequestBody TeamBody body, HttpSession s) {
        return adminOnly(s, () -> { lobby.assign(id, body.teamId()); return Map.of("ok", true); });
    }

    @PostMapping("/admin/users/{id}/stand-in")
    public ResponseEntity<?> replaceWithStandIn(@PathVariable long id, HttpSession s) {
        return adminOnly(s, () -> lobby.replaceWithStandIn(id));
    }

    @PostMapping("/admin/users/{id}/stand-in/restore")
    public ResponseEntity<?> restoreFromStandIn(@PathVariable long id, HttpSession s) {
        return adminOnly(s, () -> lobby.restoreFromStandIn(id));
    }

    @PostMapping("/admin/start")
    public ResponseEntity<?> start(HttpSession s) {
        return adminOnly(s, () -> { lobby.start(); return Map.of("ok", true); });
    }

    @PostMapping("/admin/reset-ready")
    public ResponseEntity<?> reset(@RequestBody(required = false) NextDayBody body, HttpSession s) {
        return adminOnly(s, () -> {
            lobby.resetReady(body != null && Boolean.TRUE.equals(body.regroup()));
            return Map.of("ok", true);
        });
    }

    @PostMapping("/admin/reset-tournament")
    public ResponseEntity<?> resetTournament(HttpSession s) {
        return adminOnly(s, () -> { lobby.resetTwoDayTournament(); return Map.of("ok", true); });
    }

    @PostMapping("/admin/ready-all")
    public ResponseEntity<?> readyAll(@RequestBody(required = false) ReadyAllBody body, HttpSession s) {
        boolean markAfk = body == null || body.markAfk() == null || body.markAfk();
        return adminOnly(s, () -> { lobby.readyAll(markAfk); return Map.of("ok", true, "markAfk", markAfk); });
    }

    @PostMapping("/admin/accumulation/{teamId}/roll-all")
    public ResponseEntity<?> rollAllAccumulation(@PathVariable String teamId, HttpSession s) {
        return adminOnly(s, () -> tournament.rollRemainingAccumulation(teamId, user(s)));
    }

    @PostMapping("/admin/role-vote/{teamId}/assign")
    public ResponseEntity<?> assignCurrentRole(@PathVariable String teamId,
                                               @RequestBody AdminRoleBody body, HttpSession s) {
        return adminOnly(s, () -> tournament.assignCurrentRole(
                teamId, body.role(), body.playerId(), user(s)));
    }

    @GetMapping(value = "/admin/performance/template",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> performanceTemplate() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=gmv-performance-template.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(performance.template());
    }

    @GetMapping(value = "/admin/performance/sample",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> performanceSample() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=gmv-import-sample.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(performance.sampleTemplate());
    }

    @GetMapping("/admin/performance/status")
    public ResponseEntity<?> performanceStatus(HttpSession s) { return adminOnly(s, performance::status); }

    @PostMapping(value = "/admin/performance/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importPerformance(@RequestParam("file") MultipartFile file, HttpSession s) {
        return adminOnly(s, () -> performance.importFile(file));
    }

    @PostMapping("/admin/random-group")
    public ResponseEntity<?> randomGroup(HttpSession s) { return adminOnly(s, performance::randomGroup); }

    @GetMapping("/admin/test-mode/status")
    public ResponseEntity<?> testModeStatus(HttpSession s) { return adminOnly(s, testMode::status); }

    @PostMapping("/admin/test-mode/prepare")
    public ResponseEntity<?> prepareTestMode(HttpSession s) { return adminOnly(s, testMode::prepare); }

    @PostMapping("/admin/test-mode/advance")
    public ResponseEntity<?> advanceTestMode(HttpSession s) {
        return adminOnly(s, () -> testMode.advance(user(s)));
    }

    @PostMapping("/admin/test-mode/cleanup")
    public ResponseEntity<?> cleanupTestMode(HttpSession s) { return adminOnly(s, testMode::cleanup); }

    @GetMapping("/admin/test-mode/player-view")
    public ResponseEntity<?> testModePlayerView(@RequestParam String teamId, HttpSession s) {
        return adminOnly(s, () -> testMode.playerView(teamId));
    }

    @GetMapping("/admin/test-mode/solo-candidates")
    public ResponseEntity<?> soloCandidates(HttpSession s) { return adminOnly(s, testMode::soloCandidates); }

    @PostMapping("/admin/test-mode/sandbox-players")
    public ResponseEntity<?> assignSandboxPlayers(@RequestBody SandboxPlayersBody body, HttpSession s) {
        return adminOnly(s, () -> testMode.assignSandboxPlayers(
                body.firstUsername(), body.firstTeamId(), body.firstIdentity(),
                body.secondUsername(), body.secondTeamId(), body.secondIdentity()));
    }

    private ResponseEntity<?> adminOnly(HttpSession s, Action action) {
        if (!"ADMIN".equals(s.getAttribute("role"))) return ResponseEntity.status(403).body(Map.of("error", "仅管理员可操作"));
        try { return ResponseEntity.ok(action.run()); }
        catch (IllegalStateException | IllegalArgumentException e) { return ResponseEntity.status(409).body(Map.of("error", e.getMessage())); }
    }
    private String user(HttpSession s) { return (String) s.getAttribute(AuthController.SESSION_USER); }
    private interface Action { Object run(); }
    public record ReadyBody(boolean ready) {}
    public record ReadyAllBody(Boolean markAfk) {}
    public record ChatBody(String content) {}
    public record TeamBody(String teamId) {}
    public record NextDayBody(Boolean regroup) {}
    public record PlayerActionBody(String type, java.util.List<String> selections) {}
    public record AdminRoleBody(String role, String playerId) {}
    public record SandboxPlayersBody(String firstUsername, String firstTeamId, String firstIdentity,
                                     String secondUsername, String secondTeamId, String secondIdentity) {}
}
