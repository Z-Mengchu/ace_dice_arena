package com.acedicearena.web;

import com.acedicearena.domain.UserAccount;
import com.acedicearena.service.AccountService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public static final String SESSION_USER = "loginUser";
    public static final String SESSION_DISPLAY_NAME = "displayName";
    private final AccountService accountService;
    private final Semaphore loginSlots;

    public AuthController(AccountService accountService,
                          @Value("${app.login.max-concurrent:48}") int maxConcurrentLogins) {
        this.accountService = accountService;
        this.loginSlots = new Semaphore(Math.max(1, maxConcurrentLogins), true);
    }

    @GetMapping("/config")
    public Map<String, Boolean> config() {
        return Map.of("registrationEnabled", accountService.isRegistrationEnabled());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Credentials body, HttpSession session) {
        if (!loginSlots.tryAcquire()) {
            int retryAfterMs = ThreadLocalRandom.current().nextInt(500, 1301);
            return ResponseEntity.status(429)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(Map.of("error", "当前登录人数较多，正在排队，请稍候", "retryAfterMs", retryAfterMs));
        }
        try {
            UserAccount user = accountService.login(trim(body.username()), body.password());
            setSession(session, user);
            return ResponseEntity.ok(userView(user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } finally {
            loginSlots.release();
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Registration body, HttpSession session) {
        try {
            UserAccount user = accountService.register(trim(body.username()), trim(body.displayName()), trim(body.department()), body.password());
            setSession(session, user);
            return ResponseEntity.ok(userView(user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        Object username = session.getAttribute(SESSION_USER);
        if (username == null) return ResponseEntity.status(401).body(Map.of("error", "not logged in"));
        return ResponseEntity.ok(Map.of("username", username, "displayName", session.getAttribute(SESSION_DISPLAY_NAME),
                "role", session.getAttribute("role")));
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpSession session) {
        session.invalidate();
        return Map.of("ok", true);
    }

    private void setSession(HttpSession session, UserAccount user) {
        session.setAttribute(SESSION_USER, user.getUsername());
        session.setAttribute(SESSION_DISPLAY_NAME, user.getDisplayName());
        session.setAttribute("role", user.getRole());
    }

    private Map<String, String> userView(UserAccount user) {
        return Map.of("username", user.getUsername(), "displayName", user.getDisplayName(), "role", user.getRole());
    }

    private String trim(String value) { return value == null ? null : value.trim(); }
    public record Credentials(String username, String password) {}
    public record Registration(String username, String displayName, String department, String password) {}
}
