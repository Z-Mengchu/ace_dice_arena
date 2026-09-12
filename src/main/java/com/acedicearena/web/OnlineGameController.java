package com.acedicearena.web;

import com.acedicearena.domain.UserAccount;
import com.acedicearena.service.LobbyService;
import com.acedicearena.service.OnlineGameService;
import com.acedicearena.service.ParallelTournamentService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Function;

/** 在线掷骰接口：只做会话解析、令牌归属校验与响应组装，业务逻辑在 service。 */
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

    /** 当前掷骰阶段状态视图。 */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return service.stateView();
    }

    /** 时钟探测：回显客户端时间戳并返回服务端时间，供校时采样。 */
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
            UserAccount user = requireUser(session);
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
        return ResponseEntity.ok(tournament.rollAssignmentView(requireUser(session).getUsername()));
    }

    /**
     * 偏移一律由服务端根据 /api/ping 的探测样本计算，请求体中的 offset 只为兼容旧客户端，不参与运算。
     */
    @PostMapping("/calibrate")
    public ResponseEntity<?> calibrate(@RequestBody CalibrateBody body, HttpSession session) {
        return withVerifiedToken(body.token(), session, user -> {
            OnlineGameService.Calibration calibration = service.calibrate(body.token(), body.rtt());
            return ResponseEntity.ok(Map.of("ok", true, "offset", calibration.offset(), "rtt", calibration.rtt()));
        });
    }

    /** 执行一次掷骰并返回点数与掷骰时刻。 */
    @PostMapping("/roll")
    public ResponseEntity<?> roll(@RequestBody RollBody body, HttpSession session) {
        return withVerifiedToken(body.token(), session, user -> {
            ParallelTournamentService.LiveRoll roll = service.roll(body.token(), body.clientTs());
            return ResponseEntity.ok(Map.of("die", roll.die(), "rollTs", roll.rollTs()));
        });
    }

    /**
     * 解析会话用户并校验掷骰令牌归属后执行动作；
     * 统一异常映射：令牌无效 401、参数非法 400、状态冲突 409、令牌不属于本账号 403。
     */
    private ResponseEntity<?> withVerifiedToken(String token, HttpSession session,
                                                Function<UserAccount, ResponseEntity<?>> action) {
        try {
            UserAccount user = requireUser(session);
            if (!service.ownsDevice(token, "u" + user.getId()))
                return ResponseEntity.status(403).body(Map.of("error", "当前账号没有这个掷骰令牌"));
            return action.apply(user);
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    private UserAccount requireUser(HttpSession session) {
        return lobby.requireUser((String) session.getAttribute(AuthController.SESSION_USER));
    }

    public record PingBody(String token, Double c0) {
    }

    public record CalibrateBody(String token, Double offset, Double rtt) {
    }

    public record RollBody(String token, Double clientTs) {
    }
}
