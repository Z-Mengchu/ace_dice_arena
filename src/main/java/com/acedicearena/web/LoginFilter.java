package com.acedicearena.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
@Order(20)
public class LoginFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/login", "/login.html", "/favicon.ico", "/error"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean publicApi = path.equals("/api/auth/login") || path.equals("/api/auth/register")
                || path.equals("/api/auth/config");
        HttpSession session = request.getSession(false);
        boolean loggedIn = session != null && session.getAttribute(AuthController.SESSION_USER) != null;
        if (loggedIn) {
            boolean admin = "ADMIN".equals(session.getAttribute("role"));
            boolean adminApi = path.startsWith("/api/admin/") || path.equals("/api/arm")
                    || path.equals("/api/go") || path.equals("/api/reset")
                    || path.equals("/api/game-state") && "PUT".equals(request.getMethod());
            boolean adminPage = path.equals("/admin") || path.equals("/admin.html")
                    || path.equals("/sandbox-player") || path.equals("/sandbox-player.html")
                    || path.equals("/") || path.equals("/index.html");
            if (!admin && adminApi) {
                response.setStatus(403);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"admin required\"}");
                return;
            }
            if (!admin && adminPage) {
                redirectPreservingOrigin(response, "/lobby");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (PUBLIC_PATHS.contains(path) || path.startsWith("/assets/") || publicApi) {
            chain.doFilter(request, response);
            return;
        }
        if (path.startsWith("/api/")) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"login required\"}");
        } else {
            redirectPreservingOrigin(response, "/login");
        }
    }

    /** 使用相对 Location，避免反向代理未传递外部端口时把 3004 重定向成 80。 */
    private void redirectPreservingOrigin(HttpServletResponse response, String path) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", path);
    }
}
