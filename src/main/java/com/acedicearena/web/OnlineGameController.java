package com.acedicearena.web;

import com.acedicearena.domain.UserAccount;
import com.acedicearena.service.LobbyService;
import com.acedicearena.service.OnlineGameService;
import com.acedicearena.service.ParallelTournamentService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OnlineGameController {
    private final OnlineGameService service;
    private final ParallelTournamentService tournament;
    private final LobbyService lobby;

    public OnlineGameController(OnlineGameService service, ParallelTournamentService tournament, LobbyService lobby) {
        this.service = service;
        this.tournament = tournament;
        this.lobby = lobby;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return service.stateView();
    }

    @PostMapping("/ping")
    public Map<String, Object> ping(@RequestBody(required = false) PingBody body) {
        long serverTs = service.ping(body == null ? null : body.token(), body == null ? null : body.c0());
        return Map.of("c0", body == null || body.c0() == null ? 0 : body.c0(), "s", serverTs);
    }

    /**
     * ROLL 阶段在赛队伍成员领取掷骰令牌；同一账号重复 join 会轮换令牌，旧令牌立即失效。
     */
    @PostMapping("/join")
    public ResponseEntity<?> join(HttpSession session) {
        try {
            UserAccount user = lobby.requireUser((String) session.getAttribute(AuthController.SESSION_USER));
            return ResponseEntity.ok(service.join(user.getUsername(), "u" + user.getId()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 掷骰席位查询：不在 ROLL 或本队不在赛时 eligible=false，仍然 200 返回阶段信息供客户端渲染。
     */
    @GetMapping("/roll-assignment")
    public ResponseEntity<?> rollAssignment(HttpSession session) {
        UserAccount user = lobby.requireUser((String) session.getAttribute(AuthController.SESSION_USER));
        ParallelTournamentService.RollAssignmentView view = tournament.rollAssignment(user.getUsername());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eligible", view.eligible());
        result.put("stage", view.stage());
        result.put("rollGoAt", view.rollGoAt());
        result.put("stageDeadlineAt", view.stageDeadlineAt());
        result.put("alreadyRolled", view.alreadyRolled());
        result.put("teamId", view.teamId());
        result.put("squadIndex", view.squadIndex());
        result.put("rollOpenAt", view.rollOpenAt());
        result.put("rollDeadlineAt", view.rollDeadlineAt());
        return ResponseEntity.ok(result);
    }

    /**
     * 偏移一律由服务端根据 /api/ping 的探测样本计算，请求体中的 offset 只为兼容旧客户端，不参与运算。
     */
    @PostMapping("/calibrate")
    public ResponseEntity<?> calibrate(@RequestBody CalibrateBody body, HttpSession session) {
        try {
            UserAccount user = lobby.requireUser((String) session.getAttribute(AuthController.SESSION_USER));
            if (!service.ownsDevice(body.token(), "u" + user.getId()))
                return ResponseEntity.status(403).body(Map.of("error", "当前账号没有这个掷骰令牌"));
            OnlineGameService.Calibration calibration = service.calibrate(body.token(), body.rtt());
            return ResponseEntity.ok(Map.of("ok", true, "offset", calibration.offset(), "rtt", calibration.rtt()));
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/roll")
    public ResponseEntity<?> roll(@RequestBody RollBody body, HttpSession session) {
        try {
            UserAccount user = lobby.requireUser((String) session.getAttribute(AuthController.SESSION_USER));
            if (!service.ownsDevice(body.token(), "u" + user.getId()))
                return ResponseEntity.status(403).body(Map.of("error", "当前账号没有这个掷骰令牌"));
            ParallelTournamentService.LiveRoll roll = service.roll(body.token(), body.clientTs());
            return ResponseEntity.ok(Map.of("die", roll.die(), "rollTs", roll.rollTs()));
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    public record PingBody(String token, Double c0) {
    }

    public record CalibrateBody(String token, Double offset, Double rtt) {
    }

    public record RollBody(String token, Double clientTs) {
    }
}
