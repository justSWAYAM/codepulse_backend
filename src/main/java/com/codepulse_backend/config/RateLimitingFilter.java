package com.codepulse_backend.config;

import com.codepulse_backend.auth.security.CustomUserDetails;
import com.codepulse_backend.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

/**
 * Fixed-window (per minute) request limits kept in Redis so they hold across instances.
 * Login is keyed by client IP. Run and Submit are keyed by user ID, not IP, because a
 * whole lab can sit behind one NAT address.
 * Runs after the Spring Security chain, so the authenticated user is known here.
 * If Redis is unreachable the request is let through: the per-question caps and the
 * Run semaphore still apply.
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "codepulse:ratelimit:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final SubmissionProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.getRateLimit().isEnabled()
                || !"POST".equalsIgnoreCase(request.getMethod())
                || bucketFor(request) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        Bucket bucket = bucketFor(request);
        String subject = bucket == Bucket.LOGIN ? clientIp(request) : currentUserId();

        // Unauthenticated Run/Submit is rejected by security anyway
        if (subject == null || isAllowed(bucket, subject)) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfter = WINDOW.toSeconds() - (clock.instant().getEpochSecond() % WINDOW.toSeconds());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.failure(
                        "RATE_LIMITED: Too many requests. Try again in " + retryAfter + " seconds.",
                        (String) request.getAttribute("traceId")
                )
        );
    }

    private boolean isAllowed(Bucket bucket, String subject) {
        long window = clock.instant().getEpochSecond() / WINDOW.toSeconds();
        String key = KEY_PREFIX + bucket.name().toLowerCase() + ":" + subject + ":" + window;

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, WINDOW.plusSeconds(10));
            }
            return count == null || count <= limitFor(bucket);
        } catch (Exception e) {
            log.warn("Rate limiting skipped, Redis unavailable: {}", e.getMessage());
            return true;
        }
    }

    private int limitFor(Bucket bucket) {
        SubmissionProperties.RateLimit limits = properties.getRateLimit();
        return switch (bucket) {
            case LOGIN -> limits.getLoginPerMinute();
            case RUN -> limits.getRunPerMinute();
            case SUBMIT -> limits.getSubmitPerMinute();
        };
    }

    private static Bucket bucketFor(HttpServletRequest request) {
        return switch (request.getRequestURI()) {
            case "/api/auth/login" -> Bucket.LOGIN;
            case "/api/submissions/run" -> Bucket.RUN;
            case "/api/submissions/submit" -> Bucket.SUBMIT;
            default -> null;
        };
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails user) {
            return user.getId().toString();
        }
        return null;
    }

    // remoteAddr only: X-Forwarded-For is client-controlled unless a trusted proxy sets it
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private enum Bucket { LOGIN, RUN, SUBMIT }
}
