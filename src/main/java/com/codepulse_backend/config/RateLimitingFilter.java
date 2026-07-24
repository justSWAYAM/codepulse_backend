package com.codepulse_backend.config;

import jakarta.servlet.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(2)
public class RateLimitingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // TODO Module 1: Redis token-bucket check on /auth/login and /submissions/run
        chain.doFilter(request, response);
    }
}
