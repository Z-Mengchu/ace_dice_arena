package com.acedicearena.web;

import com.acedicearena.domain.BattleReport;
import com.acedicearena.service.GameDataService;
import com.acedicearena.service.GameDataService.FetchStatus;
import com.acedicearena.service.GameDataService.GameStateResult;
import com.acedicearena.service.GameDataService.MatchDetailResult;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 比赛数据接口：只负责会话参数提取与 HTTP 响应组装，业务逻辑在 GameDataService。 */
@RestController
@RequestMapping("/api")
public class GameDataController {
    private static final String STATE_CACHE_CONTROL = "private, no-cache";
    private final GameDataService gameData;

    public GameDataController(GameDataService gameData) {
        this.gameData = gameData;
    }

    /** 读取整局状态：支持 ETag 条件请求（304），普通用户返回按队脱敏的视图。 */
    @GetMapping("/game-state")
    public ResponseEntity<?> getGameState(
            @RequestParam(required = false) String scope,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpSession session) {
        GameStateResult result = gameData.gameState(username(session), ordinaryUser(session), scope, ifNoneMatch);
        if (result.status() == FetchStatus.NO_CONTENT) return ResponseEntity.noContent().build();
        if (result.status() == FetchStatus.NOT_MODIFIED) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(result.etag())
                    .header(HttpHeaders.CACHE_CONTROL, STATE_CACHE_CONTROL).build();
        }
        return ResponseEntity.ok().eTag(result.etag()).header(HttpHeaders.CACHE_CONTROL, STATE_CACHE_CONTROL)
                .body(Map.of("state", result.state(), "version", result.version()));
    }

    /** 供轻量单元测试与内部调用保留的无条件读取入口。 */
    public ResponseEntity<?> getGameState(HttpSession session) {
        return getGameState(null, null, session);
    }

    /** 单场对局详情：行内只有摘要时从归档表回填完整 rounds 战力明细。 */
    @GetMapping("/game-state/matches/{matchId}")
    public ResponseEntity<?> getMatchDetail(@PathVariable String matchId, HttpSession session) {
        MatchDetailResult result = gameData.matchDetail(matchId, username(session), ordinaryUser(session));
        if (result.status() == FetchStatus.NO_CONTENT) return ResponseEntity.noContent().build();
        if (result.status() == FetchStatus.NOT_FOUND) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(result.match());
    }

    /** 保存整局状态并返回新版本号。 */
    @PutMapping("/game-state")
    public Map<String, Object> saveGameState(@RequestBody JsonNode state, HttpSession session) {
        return gameData.saveGameState(state, username(session));
    }

    /** 提交一条战报。 */
    @PostMapping("/battle-reports")
    public ResponseEntity<?> addBattleReport(@RequestBody ReportBody body, HttpSession session) {
        return gameData.addBattleReport(body.content(), username(session))
                .<ResponseEntity<?>>map(id -> ResponseEntity.ok(Map.of("id", id)))
                .orElseGet(() -> ResponseEntity.badRequest().body(Map.of("error", "empty content")));
    }

    /** 最近 300 条战报。 */
    @GetMapping("/battle-reports")
    public List<BattleReport> battleReports() {
        return gameData.battleReports();
    }

    private static boolean ordinaryUser(HttpSession session) {
        return "USER".equals(session.getAttribute("role"));
    }

    private static String username(HttpSession session) {
        return (String) session.getAttribute(AuthController.SESSION_USER);
    }

    public record ReportBody(String content) {
    }
}
