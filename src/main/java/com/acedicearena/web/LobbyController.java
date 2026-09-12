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

/** 大厅与管理员接口：只做会话校验与 HTTP 响应组装，业务逻辑委托各 service。 */
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
        this.lobby = lobby;
        this.events = events;
        this.playerActions = playerActions;
        this.performance = performance;
        this.testMode = testMode;
        this.tournament = tournament;
    }

    /** 大厅视图：名单、准备状态与当前阶段。 */
    @GetMapping("/lobby")
    public LobbyService.LobbyView lobby(HttpSession s) {
        return lobby.view(user(s));
    }

    /** 玩家设置/取消准备；阶段不允许时返回 409。 */
    @PostMapping("/lobby/ready")
    public ResponseEntity<?> ready(@RequestBody ReadyBody body, HttpSession s) {
        try {
            lobby.ready(user(s), body.ready());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** 取消 AFK 标记；状态不允许时返回 409。 */
    @PostMapping("/lobby/afk/cancel")
    public ResponseEntity<?> cancelAfk(HttpSession s) {
        try {
            return ResponseEntity.ok(lobby.cancelAfk(user(s)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** 大厅事件 SSE 订阅。 */
    @GetMapping("/lobby/events")
    public SseEmitter events(HttpSession s) {
        UserAccount u = lobby.requireUser(user(s));
        return events.subscribe(u.getUsername(), u.getTeamId(), u.getRole());
    }

    /** 发送队伍聊天；观战用户 409，长度非法 400（校验在 LobbyService.chat）。 */
    @PostMapping("/lobby/chat")
    public ResponseEntity<?> chat(@RequestBody ChatBody body, HttpSession s) {
        try {
            lobby.chat(user(s), body.content());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 提交玩家操作（如选人）；非法参数 400，状态冲突 409。 */
    @PostMapping("/lobby/player-action")
    public ResponseEntity<?> playerAction(@RequestBody PlayerActionBody body, HttpSession s) {
        try {
            return ResponseEntity.ok(playerActions.submit(user(s), body.type(), body.selections()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 管理员仪表盘视图。 */
    @GetMapping("/admin/dashboard")
    public ResponseEntity<?> admin(HttpSession s) {
        return adminOnly(s, () -> lobby.adminView(user(s)));
    }

    /** 把用户分配到指定队伍。 */
    @PutMapping("/admin/users/{id}/team")
    public ResponseEntity<?> assign(@PathVariable long id, @RequestBody TeamBody body, HttpSession s) {
        return adminOnly(s, () -> {
            lobby.assign(id, body.teamId());
            return Map.of("ok", true);
        });
    }

    /** 用替补顶替该用户上场。 */
    @PostMapping("/admin/users/{id}/stand-in")
    public ResponseEntity<?> replaceWithStandIn(@PathVariable long id, HttpSession s) {
        return adminOnly(s, () -> lobby.replaceWithStandIn(id));
    }

    /** 恢复原用户，收回替补。 */
    @PostMapping("/admin/users/{id}/stand-in/restore")
    public ResponseEntity<?> restoreFromStandIn(@PathVariable long id, HttpSession s) {
        return adminOnly(s, () -> lobby.restoreFromStandIn(id));
    }

    /** 开始比赛。 */
    @PostMapping("/admin/start")
    public ResponseEntity<?> start(HttpSession s) {
        return adminOnly(s, () -> {
            lobby.start();
            return Map.of("ok", true);
        });
    }

    /** 重置全员准备状态；regroup=true 时重新分组。 */
    @PostMapping("/admin/reset-ready")
    public ResponseEntity<?> reset(@RequestBody(required = false) NextDayBody body, HttpSession s) {
        return adminOnly(s, () -> {
            lobby.resetReady(body != null && Boolean.TRUE.equals(body.regroup()));
            return Map.of("ok", true);
        });
    }

    /** 重置整届两日锦标赛。 */
    @PostMapping("/admin/reset-tournament")
    public ResponseEntity<?> resetTournament(HttpSession s) {
        return adminOnly(s, () -> {
            lobby.resetTwoDayTournament();
            return Map.of("ok", true);
        });
    }

    /** 开启加赛环节。 */
    @PostMapping("/admin/start-overtime")
    public ResponseEntity<?> startOvertime(HttpSession s) {
        return adminOnly(s, () -> {
            lobby.startOvertime();
            return Map.of("ok", true);
        });
    }

    /** 一键全员准备；markAfk 默认 true（未到场的标记 AFK）。 */
    @PostMapping("/admin/ready-all")
    public ResponseEntity<?> readyAll(@RequestBody(required = false) ReadyAllBody body, HttpSession s) {
        boolean markAfk = body == null || body.markAfk() == null || body.markAfk();
        return adminOnly(s, () -> {
            lobby.readyAll(markAfk);
            return Map.of("ok", true, "markAfk", markAfk);
        });
    }

    /** 管理员代队伍指定当前环节的角色人选。 */
    @PostMapping("/admin/role-vote/{teamId}/assign")
    public ResponseEntity<?> assignCurrentRole(@PathVariable String teamId,
                                               @RequestBody AdminRoleBody body, HttpSession s) {
        return adminOnly(s, () -> tournament.assignCurrentRole(
                teamId, body.role(), body.playerId(), user(s)));
    }

    /** 下载业绩导入空白模板（xlsx）。 */
    @GetMapping(value = "/admin/performance/template",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> performanceTemplate() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=gmv-performance-template.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(performance.template());
    }

    /** 下载业绩导入样例文件（xlsx）。 */
    @GetMapping(value = "/admin/performance/sample",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> performanceSample() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=gmv-import-sample.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(performance.sampleTemplate());
    }

    /** 业绩导入状态查询。 */
    @GetMapping("/admin/performance/status")
    public ResponseEntity<?> performanceStatus(HttpSession s) {
        return adminOnly(s, performance::status);
    }

    /** 上传 xlsx 导入业绩数据。 */
    @PostMapping(value = "/admin/performance/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importPerformance(@RequestParam("file") MultipartFile file, HttpSession s) {
        return adminOnly(s, () -> performance.importFile(file));
    }

    /** 按业绩数据随机分组。 */
    @PostMapping("/admin/random-group")
    public ResponseEntity<?> randomGroup(HttpSession s) {
        return adminOnly(s, performance::randomGroup);
    }

    /**
     * 现场兜底：把该场次当前等待环节的截止时间提前，由定时扫描按同一套超时逻辑推进。
     */
    @PostMapping("/admin/matches/{matchId}/force")
    public ResponseEntity<?> forceMatch(@PathVariable String matchId, HttpSession s) {
        return adminOnly(s, () -> tournament.forceMatch(matchId));
    }

    /**
     * 单场加赛：三连环全平的场次由管理员触发两队重赛。
     */
    @PostMapping("/admin/matches/{matchId}/rematch")
    public ResponseEntity<?> rematch(@PathVariable String matchId, HttpSession s) {
        return adminOnly(s, () -> {
            tournament.rematch(user(s), matchId);
            return Map.of("ok", true);
        });
    }

    /** 沙盘模式状态查询。 */
    @GetMapping("/admin/test-mode/status")
    public ResponseEntity<?> testModeStatus(HttpSession s) {
        return adminOnly(s, testMode::status);
    }

    /** 准备沙盘：生成测试账号与初始状态。 */
    @PostMapping("/admin/test-mode/prepare")
    public ResponseEntity<?> prepareTestMode(HttpSession s) {
        return adminOnly(s, testMode::prepare);
    }

    /** 推进沙盘到下一阶段。 */
    @PostMapping("/admin/test-mode/advance")
    public ResponseEntity<?> advanceTestMode(HttpSession s) {
        return adminOnly(s, () -> testMode.advance(user(s)));
    }

    /** 清理沙盘数据。 */
    @PostMapping("/admin/test-mode/cleanup")
    public ResponseEntity<?> cleanupTestMode(HttpSession s) {
        return adminOnly(s, testMode::cleanup);
    }

    /** 以指定队伍视角预览沙盘玩家端。 */
    @GetMapping("/admin/test-mode/player-view")
    public ResponseEntity<?> testModePlayerView(@RequestParam String teamId, HttpSession s) {
        return adminOnly(s, () -> testMode.playerView(teamId));
    }

    /** 单人沙盘候选人列表。 */
    @GetMapping("/admin/test-mode/solo-candidates")
    public ResponseEntity<?> soloCandidates(HttpSession s) {
        return adminOnly(s, testMode::soloCandidates);
    }

    /** 指派私密沙盘的参赛账号与身份。 */
    @PostMapping("/admin/test-mode/sandbox-players")
    public ResponseEntity<?> assignSandboxPlayers(@RequestBody SandboxPlayersBody body, HttpSession s) {
        return adminOnly(s, () -> testMode.assignSandboxPlayers(
                body.firstUsername(), body.firstTeamId(), body.firstIdentity(),
                body.secondUsername(), body.secondTeamId(), body.secondIdentity()));
    }

    /** 管理员守卫：非 ADMIN 返回 403，业务异常统一映射 409。 */
    private ResponseEntity<?> adminOnly(HttpSession s, Action action) {
        if (!"ADMIN".equals(s.getAttribute("role")))
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员可操作"));
        try {
            return ResponseEntity.ok(action.run());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    private String user(HttpSession s) {
        return (String) s.getAttribute(AuthController.SESSION_USER);
    }

    private interface Action {
        Object run();
    }

    public record ReadyBody(boolean ready) {
    }

    public record ReadyAllBody(Boolean markAfk) {
    }

    public record ChatBody(String content) {
    }

    public record TeamBody(String teamId) {
    }

    public record NextDayBody(Boolean regroup) {
    }

    public record PlayerActionBody(String type, java.util.List<String> selections) {
    }

    public record AdminRoleBody(String role, String playerId) {
    }

    public record SandboxPlayersBody(String firstUsername, String firstTeamId, String firstIdentity,
                                     String secondUsername, String secondTeamId, String secondIdentity) {
    }
}
