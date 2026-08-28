package com.acedicearena.web;

import com.acedicearena.domain.RequestAudit;
import com.acedicearena.service.RequestAuditWriter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

@Component
@Order(10)
public class RequestAuditFilter extends OncePerRequestFilter {
    private final RequestAuditWriter writer;

    public RequestAuditFilter(RequestAuditWriter writer) { this.writer = writer; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Instant startedAt = Instant.now();
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            HttpSession session = request.getSession(false);
            String username = session == null ? null : (String) session.getAttribute(AuthController.SESSION_USER);
            // 记录端点但不记录查询参数，避免把 SSE 设备 token 写入审计表。
            String path = request.getRequestURI();
            try {
                writer.submit(new RequestAudit(request.getMethod(), path, username, request.getRemoteAddr(),
                        response.getStatus(), (System.nanoTime() - started) / 1_000_000, startedAt));
            } catch (RuntimeException ignored) {
                // 审计失败不能阻断现场比赛请求。
            }
        }
    }
}
