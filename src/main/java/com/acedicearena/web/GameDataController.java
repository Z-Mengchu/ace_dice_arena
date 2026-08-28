package com.acedicearena.web;

import com.acedicearena.domain.BattleReport;
import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.repository.BattleReportRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.acedicearena.service.AdminTestModeService;
import com.acedicearena.service.LobbyEventService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.time.Instant;

@RestController
@RequestMapping("/api")
public class GameDataController {
    private static final long STATE_ID = 1L;
    private final GameStateRepository gameStateRepository;
    private final BattleReportRepository battleReportRepository;
    private final ObjectMapper objectMapper;
    private final LobbyEventService lobbyEvents;
    private final UserAccountRepository users;
    private final long stateCacheTtlMs;
    private final Object stateCacheLock = new Object();
    private volatile StateSnapshot stateCache;
    private volatile TestUserSnapshot testUserCache;

    public GameDataController(GameStateRepository gameStateRepository,
                              BattleReportRepository battleReportRepository,
                              ObjectMapper objectMapper,
                              LobbyEventService lobbyEvents,
                              UserAccountRepository users,
                              @Value("${app.cache.game-state-ttl-ms:250}") long stateCacheTtlMs) {
        this.gameStateRepository = gameStateRepository;
        this.battleReportRepository = battleReportRepository;
        this.objectMapper = objectMapper;
        this.lobbyEvents = lobbyEvents;
        this.users = users;
        this.stateCacheTtlMs = Math.max(0, stateCacheTtlMs);
    }

    @GetMapping("/game-state")
    public ResponseEntity<?> getGameState(HttpSession session) {
        StateSnapshot snapshot = gameState();
        boolean ordinaryUser = "USER".equals(session.getAttribute("role"));
        if (ordinaryUser && hasTestUsers()) {
            String username = (String) session.getAttribute(AuthController.SESSION_USER);
            boolean sandboxPlayer = false;
            if (snapshot.present()) {
                for (JsonNode player : snapshot.state().path("sandboxPlayers"))
                    if (username.equals(player.path("username").asText())) sandboxPlayer = true;
                if (username.equals(snapshot.state().at("/sandboxSolo/username").asText(null))) sandboxPlayer = true;
            }
            if (!sandboxPlayer) return ResponseEntity.noContent().build();
        }
        if (!snapshot.present()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(Map.of(
                "state", snapshot.state(), "version", snapshot.version(),
                "updatedAt", snapshot.updatedAt(), "updatedBy", snapshot.updatedBy()));
    }

    @PutMapping("/game-state")
    @Transactional
    public Map<String, Object> saveGameState(@RequestBody JsonNode state, HttpSession session) {
        String username = (String) session.getAttribute(AuthController.SESSION_USER);
        String content = state.toString();
        GameStateRecord record = gameStateRepository.findById(STATE_ID).orElse(null);
        if (record == null) record = new GameStateRecord(STATE_ID, content, username);
        else record.update(content, username);
        gameStateRepository.save(record);
        stateCache = null;
        lobbyEvents.gameChanged();
        return Map.of("ok", true, "version", record.getVersion());
    }

    @PostMapping("/battle-reports")
    public ResponseEntity<?> addBattleReport(@RequestBody ReportBody body, HttpSession session) {
        if (body.content() == null || body.content().isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "empty content"));
        String username = (String) session.getAttribute(AuthController.SESSION_USER);
        BattleReport saved = battleReportRepository.save(new BattleReport(body.content(), username));
        return ResponseEntity.ok(Map.of("id", saved.getId()));
    }

    @GetMapping("/battle-reports")
    public List<BattleReport> battleReports() { return battleReportRepository.findTop300ByOrderByIdDesc(); }

    private JsonNode parse(String json) {
        try { return objectMapper.readTree(json); }
        catch (Exception e) { return objectMapper.createObjectNode(); }
    }

    private StateSnapshot gameState() {
        long now = System.currentTimeMillis();
        StateSnapshot cached = stateCache;
        if (stateCacheTtlMs > 0 && cached != null && now - cached.loadedAt() < stateCacheTtlMs) return cached;
        synchronized (stateCacheLock) {
            cached = stateCache;
            now = System.currentTimeMillis();
            if (stateCacheTtlMs > 0 && cached != null && now - cached.loadedAt() < stateCacheTtlMs) return cached;
            long loadedAt = now;
            StateSnapshot loaded = gameStateRepository.findById(STATE_ID)
                    .map(record -> new StateSnapshot(true, parse(record.getContent()), record.getVersion(),
                            record.getUpdatedAt(), record.getUpdatedBy() == null ? "" : record.getUpdatedBy(), loadedAt))
                    .orElseGet(() -> new StateSnapshot(false, objectMapper.createObjectNode(), 0,
                            Instant.EPOCH, "", loadedAt));
            stateCache = loaded;
            return loaded;
        }
    }

    private boolean hasTestUsers() {
        long now = System.currentTimeMillis();
        TestUserSnapshot cached = testUserCache;
        if (stateCacheTtlMs > 0 && cached != null && now - cached.loadedAt() < 1000) return cached.present();
        synchronized (stateCacheLock) {
            cached = testUserCache;
            now = System.currentTimeMillis();
            if (stateCacheTtlMs > 0 && cached != null && now - cached.loadedAt() < 1000) return cached.present();
            boolean present = users.existsByUsernameStartingWith(AdminTestModeService.USERNAME_PREFIX);
            testUserCache = new TestUserSnapshot(present, now);
            return present;
        }
    }

    public record ReportBody(String content) {}
    private record StateSnapshot(boolean present, JsonNode state, long version, Instant updatedAt,
                                 String updatedBy, long loadedAt) {}
    private record TestUserSnapshot(boolean present, long loadedAt) {}
}
