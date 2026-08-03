package org.example.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(3, 60, new ObjectMapper());
    }

    @Test
    void allowsRequestsUpToCapacityThenBlocks() throws Exception {
        for (int i = 0; i < 3; i++) {
            AtomicInteger chainCalls = new AtomicInteger();
            filter.doFilter(loginRequest("10.0.0.1"), new MockHttpServletResponse(),
                    (req, res) -> chainCalls.incrementAndGet());

            assertThat(chainCalls.get()).isEqualTo(1);
        }

        AtomicInteger chainCalls = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(loginRequest("10.0.0.1"), response, (req, res) -> chainCalls.incrementAndGet());

        assertThat(chainCalls.get()).isZero();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("Too many requests");
    }

    @Test
    void tracksSeparateBucketsPerClientIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(loginRequest("1.1.1.1"), new MockHttpServletResponse(), (req, res) -> {});
        }

        // a different IP has its own, untouched allowance even though 1.1.1.1 is exhausted
        AtomicInteger chainCalls = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(loginRequest("2.2.2.2"), response, (req, res) -> chainCalls.incrementAndGet());

        assertThat(chainCalls.get()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void tracksSeparateBucketsPerPathForTheSameClientIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(loginRequest("3.3.3.3"), new MockHttpServletResponse(), (req, res) -> {});
        }

        // /register has its own bucket even though /login is exhausted for the same IP
        AtomicInteger chainCalls = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(registerRequest("3.3.3.3"), response, (req, res) -> chainCalls.incrementAndGet());

        assertThat(chainCalls.get()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void alsoLimitsThePasswordChangeEndpoint() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(changePasswordRequest("4.4.4.4"), new MockHttpServletResponse(), (req, res) -> {});
        }

        AtomicInteger chainCalls = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(changePasswordRequest("4.4.4.4"), response, (req, res) -> chainCalls.incrementAndGet());

        assertThat(chainCalls.get()).isZero();
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void doesNotRateLimitOtherPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRemoteAddr("9.9.9.9");
        AtomicInteger chainCalls = new AtomicInteger();

        for (int i = 0; i < 10; i++) {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> chainCalls.incrementAndGet());
        }

        assertThat(chainCalls.get()).isEqualTo(10);
    }

    private MockHttpServletRequest loginRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletRequest registerRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/register");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletRequest changePasswordRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/users/me/password");
        request.setRemoteAddr(ip);
        return request;
    }
}
