package org.example.security;

import org.example.service.AuthService;
import org.example.util.JwtUtil;
import org.example.util.TestRsaKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final TestRsaKeys.Pair KEYS = TestRsaKeys.generate();

    private final JwtUtil jwtUtil = new JwtUtil(KEYS.privateKeyBase64(), KEYS.publicKeyBase64(), 60_000);

    @Mock
    private AuthService authService;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtUtil, authService);
        lenient().when(authService.isSessionValid(any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void populatesSecurityContextForValidBearerTokenWithALiveSession() throws Exception {
        String token = jwtUtil.generateToken("alice");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("alice");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
    }

    @Test
    void leavesContextEmptyWhenSessionHasBeenInvalidated() throws Exception {
        when(authService.isSessionValid(eq("alice"), any())).thenReturn(false);
        String token = jwtUtil.generateToken("alice");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void leavesContextEmptyWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authService, never()).isSessionValid(any(), any());
    }

    @Test
    void leavesContextEmptyForMalformedToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-real-token");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authService, never()).isSessionValid(any(), any());
    }

    @Test
    void leavesContextEmptyForExpiredToken() throws Exception {
        JwtUtil shortLived = new JwtUtil(KEYS.privateKeyBase64(), KEYS.publicKeyBase64(), 1);
        String token = shortLived.generateToken("alice");
        Thread.sleep(20);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authService, never()).isSessionValid(any(), any());
    }

    @Test
    void alwaysContinuesFilterChainRegardlessOfTokenValidity() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer garbage");
        boolean[] called = {false};

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> called[0] = true);

        assertThat(called[0]).isTrue();
    }
}
