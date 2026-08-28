package com.acedicearena.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;

/** 避免为这个小项目额外引入 Spring Security，仅提供一次请求过滤的轻量基类。 */
public abstract class OncePerRequestFilter implements Filter {
    private final String key = getClass().getName() + ".FILTERED";

    @Override
    public final void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        if (http.getAttribute(key) != null) { chain.doFilter(request, response); return; }
        http.setAttribute(key, Boolean.TRUE);
        doFilterInternal(http, (HttpServletResponse) response, chain);
    }

    protected abstract void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException;
}
