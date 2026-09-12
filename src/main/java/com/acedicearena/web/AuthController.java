package com.acedicearena.web;

import com.acedicearena.domain.UserAccount;
import com.acedicearena.service.AccountService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** 认证接口：登录/注册/当前用户/登出。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public static final String SESSION_USER = "loginUser";
    public static final String SESSION_DISPLAY_NAME = "displayName";
    /** 会话里保存的账号主键；/api/auth/me 需要向玩家端暴露 id 以在整局状态里定位"自己"。 */
    public static final String SESSION_USER_ID = "userId";
    private final AccountService accountService;

    public AuthController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** 前端登录页配置：是否开放注册。 */
    @GetMapping("/config")
    public Map<String, Boolean> config() {
        return Map.of("registrationEnabled", accountService.isRegistrationEnabled());
    }

    /** 登录：并发额度满返回 429 排队提示，凭证错误返回 400。 */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Credentials body, HttpSession session) {
        try {
            UserAccount user = accountService.tryLogin(trim(body.username()), body.password()).orElse(null);
            if (user == null) {
                int retryAfterMs = ThreadLocalRandom.current().nextInt(500, 1301);
                return ResponseEntity.status(429)
                        .header(HttpHeaders.RETRY_AFTER, "1")
                        .body(Map.of("error", "当前登录人数较多，正在排队，请稍候", "retryAfterMs", retryAfterMs));
            }
            setSession(session, user);
            return ResponseEntity.ok(userView(user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 注册并直接登录。 */
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

    /** 当前登录用户信息；未登录返回 401。 */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        Object username = session.getAttribute(SESSION_USER);
        if (username == null) return ResponseEntity.status(401).body(Map.of("error", "not logged in"));
        // 用 HashMap 语义避免 Map.of 遇到缺失属性抛 NPE（旧会话未存 userId 时兜底为 null）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", session.getAttribute(SESSION_USER_ID));
        result.put("username", username);
        result.put("displayName", session.getAttribute(SESSION_DISPLAY_NAME));
        result.put("role", session.getAttribute("role"));
        return ResponseEntity.ok(result);
    }

    /** 登出并销毁会话。 */
    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpSession session) {
        session.invalidate();
        return Map.of("ok", true);
    }

    private void setSession(HttpSession session, UserAccount user) {
        session.setAttribute(SESSION_USER, user.getUsername());
        session.setAttribute(SESSION_DISPLAY_NAME, user.getDisplayName());
        session.setAttribute(SESSION_USER_ID, user.getId());
        session.setAttribute("role", user.getRole());
    }

    private Map<String, String> userView(UserAccount user) {
        return Map.of("username", user.getUsername(), "displayName", user.getDisplayName(), "role", user.getRole());
    }

    private String trim(String value) { return value == null ? null : value.trim(); }
    public record Credentials(String username, String password) {}
    public record Registration(String username, String displayName, String department, String password) {}
}
