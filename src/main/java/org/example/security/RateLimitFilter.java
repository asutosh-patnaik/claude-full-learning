package org.example.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.dto.MessageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Per-client-IP, per-endpoint request cap on endpoints where an attacker gets to try a secret
 * repeatedly: /login and /register (credential brute-forcing / spam), and /users/me/password
 * (a stolen-but-valid token still requires knowing the current password, so without this an
 * attacker holding one could brute-force it). Buckets live in a bounded, expiring cache rather
 * than a plain map, so the limiter's own memory use can't be turned into a DoS vector by
 * rotating source IPs.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of("/login", "/register", "/users/me/password");

    private final int capacity;
    private final int refillSeconds;
    private final Cache<String, Bucket> buckets;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            @Value("${ratelimit.capacity:5}") int capacity,
            @Value("${ratelimit.refill-seconds:60}") int refillSeconds,
            ObjectMapper objectMapper) {
        this.capacity = capacity;
        this.refillSeconds = refillSeconds;
        this.objectMapper = objectMapper;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10))
                .maximumSize(100_000)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = clientIp(request) + "|" + request.getRequestURI();
        Bucket bucket = buckets.get(key, k -> newBucket());

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                new MessageResponse("Too many requests, please try again later"));
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, Duration.ofSeconds(refillSeconds)));
        return Bucket.builder().addLimit(limit).build();
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
