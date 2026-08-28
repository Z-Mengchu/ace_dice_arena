package com.acedicearena.web;

import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.service.LobbyService;
import com.acedicearena.service.OnlineGameService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OnlineGameController {
    private final OnlineGameService service;
    private final LobbyService lobby;
    private final GameStateRepository gameStates;
    private final ObjectMapper mapper;

    public OnlineGameController(OnlineGameService service, LobbyService lobby,
                                GameStateRepository gameStates, ObjectMapper mapper) {
        this.service = service; this.lobby = lobby; this.gameStates = gameStates; this.mapper = mapper;
    }

    @GetMapping("/state")
    public Map<String, Object> state() { return service.stateView(); }

    @GetMapping("/events")
    public ResponseEntity<?> events(@RequestParam(defaultValue = "") String token) {
        try { return ResponseEntity.ok(service.subscribe(token)); }
        catch (SecurityException e) { return ResponseEntity.status(401).body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/ping")
    public Map<String, Object> ping(@RequestBody(required = false) PingBody body) {
        long serverTs = service.ping(body == null ? null : body.token(), body == null ? null : body.c0());
        return Map.of("c0", body == null || body.c0() == null ? 0 : body.c0(), "s", serverTs);
    }

    @PostMapping("/join")
    public ResponseEntity<?> join(@RequestBody JoinBody body, HttpSession session) {
        try {
            UserAccount user = lobby.requireUser((String) session.getAttribute(AuthController.SESSION_USER));
            RollAssignment assignment = assignment(user);
            if (!assignment.eligible()) return ResponseEntity.status(403).body(Map.of("error", "你不在本局五人出战阵容中"));
            return ResponseEntity.ok(service.join(assignment.teamId(), assignment.slot(), user.getDisplayName(), "u" + user.getId()));
        }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        catch (IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error", e.getMessage())); }
    }

    @GetMapping("/roll-assignment")
    public ResponseEntity<?> rollAssignment(HttpSession session) {
        try {
            UserAccount user = lobby.requireUser((String) session.getAttribute(AuthController.SESSION_USER));
            RollAssignment assignment = assignment(user);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("eligible", assignment.eligible()); result.put("teamId", assignment.teamId());
            result.put("slot", assignment.slot()); result.put("phase", assignment.phase());
            result.put("gameId", assignment.gameId()); result.put("matchId", assignment.matchId());
            result.put("round", assignment.round());
            result.put("lineup", assignment.lineup());
            result.put("captain", assignment.captain());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** 偏移一律由服务端根据 /api/ping 的探测样本计算，请求体中的 offset 只为兼容旧客户端，不参与运算。 */
    @PostMapping("/calibrate")
    public ResponseEntity<?> calibrate(@RequestBody CalibrateBody body, HttpSession session) {
        try {
            UserAccount user = lobby.requireUser((String) session.getAttribute(AuthController.SESSION_USER));
            if (!service.ownsDevice(body.token(), "u" + user.getId()))
                return ResponseEntity.status(403).body(Map.of("error", "当前账号没有这个掷骰席位"));
            OnlineGameService.Calibration calibration = service.calibrate(body.token(), body.rtt());
            return ResponseEntity.ok(Map.of("ok", true, "offset", calibration.offset(), "rtt", calibration.rtt()));
        }
        catch (SecurityException e) { return ResponseEntity.status(401).body(Map.of("error", e.getMessage())); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        catch (IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/player-ready")
    public ResponseEntity<?> playerReady(@RequestBody PlayerReadyBody body, HttpSession session) {
        try {
            UserAccount user = lobby.requireUser((String) session.getAttribute(AuthController.SESSION_USER));
            RollAssignment assignment = assignment(user);
            if (!assignment.eligible() || !service.matchesAssignment(body.token(), assignment.teamId(), assignment.slot()))
                return ResponseEntity.status(403).body(Map.of("error", "当前账号没有本局备战席位"));
            service.ready(body.token(), body.ready());
            return ResponseEntity.ok(Map.of("ok", true, "ready", body.ready()));
        } catch (SecurityException e) { return ResponseEntity.status(401).body(Map.of("error", e.getMessage())); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        catch (IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/arm")
    public ResponseEntity<?> arm(@RequestBody TeamBody body) {
        try { service.arm(body.teamId()); return ResponseEntity.ok(Map.of("ok", true)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/go")
    public ResponseEntity<?> go() {
        try { return ResponseEntity.ok(Map.of("ok", true, "goTs", service.go())); }
        catch (IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/roll")
    public ResponseEntity<?> roll(@RequestBody RollBody body, HttpSession session) {
        try {
            UserAccount user = lobby.requireUser((String) session.getAttribute(AuthController.SESSION_USER));
            RollAssignment assignment = assignment(user);
            if (!assignment.eligible() || !service.matchesAssignment(body.token(), assignment.teamId(), assignment.slot()))
                return ResponseEntity.status(403).body(Map.of("error", "当前账号没有本局投骰席位"));
            service.roll(body.token(), body.clientTs());
            return ResponseEntity.ok(Map.of("ok", true));
        }
        catch (SecurityException e) { return ResponseEntity.status(401).body(Map.of("error", e.getMessage())); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        catch (IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/reset")
    public Map<String, Boolean> reset() { service.reset(); return Map.of("ok", true); }

    private RollAssignment assignment(UserAccount user) {
        if (!"USER".equals(user.getRole()) || user.getTeamId() == null)
            throw new IllegalStateException("当前账号不在参赛队伍中");
        JsonNode root = gameStates.findById(1L).map(record -> {
            try { return mapper.readTree(record.getContent()); }
            catch (Exception e) { throw new IllegalStateException("比赛状态无法读取"); }
        }).orElseThrow(() -> new IllegalStateException("比赛尚未开始"));
        JsonNode active = null;
        for (JsonNode match : root.path("matches")) {
            if ("active".equals(match.path("status").asText())
                    && (user.getTeamId().equals(match.path("a").asText()) || user.getTeamId().equals(match.path("b").asText()))) {
                active = match; break;
            }
        }
        if (active == null) throw new IllegalStateException("本队当前没有进行中的比赛");
        String side = user.getTeamId().equals(active.path("a").asText()) ? "A" : "B";
        JsonNode selected = active.at("/lineups/" + side);
        List<Map<String, Object>> lineup = new ArrayList<>();
        int assignedSlot = 0;
        for (int index = 0; selected.isArray() && index < selected.size(); index++) {
            String playerId = selected.get(index).asText();
            JsonNode player = findPlayer(root, user.getTeamId(), playerId);
            lineup.add(Map.of("id", playerId, "name", player.path("name").asText(), "slot", index + 1));
            if (playerId.equals("u" + user.getId())) assignedSlot = index + 1;
        }
        String phase = active.path("phase").asText();
        if ("ATTACKING".equals(phase)) {
            String sidePhase = active.at("/sidePhases/" + side).asText("CONFIRM");
            phase = "WAITING".equals(sidePhase) ? "WAITING" : sidePhase + "_" + side;
        }
        String captain = findTeam(root, user.getTeamId()).at("/roles/captain").asText();
        return new RollAssignment(assignedSlot > 0, user.getTeamId(), assignedSlot, phase,
                root.path("startedAt").asText("game"), active.path("id").asText(), active.path("round").asInt(1), lineup,
                captain.equals("u" + user.getId()));
    }

    private JsonNode findTeam(JsonNode root, String teamId) {
        for (JsonNode team : root.path("teams")) if (teamId.equals(team.path("id").asText())) return team;
        return mapper.createObjectNode();
    }

    private JsonNode findPlayer(JsonNode root, String teamId, String playerId) {
        for (JsonNode team : root.path("teams")) if (teamId.equals(team.path("id").asText()))
            for (JsonNode player : team.path("players")) if (playerId.equals(player.path("id").asText())) return player;
        return mapper.createObjectNode().put("name", playerId);
    }

    public record PingBody(String token, Double c0) {}
    public record JoinBody(String teamId, Integer slot, String name) {}
    public record CalibrateBody(String token, Double offset, Double rtt) {}
    public record PlayerReadyBody(String token, boolean ready) {}
    public record TeamBody(String teamId) {}
    public record RollBody(String token, Double clientTs) {}
    private record RollAssignment(boolean eligible, String teamId, int slot, String phase,
                                  String gameId, String matchId, int round,
                                  List<Map<String, Object>> lineup, boolean captain) {}
}
