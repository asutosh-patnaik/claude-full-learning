package org.example.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private static final String SECRET = "unit-test-signing-secret-at-least-32-bytes-long";

    private JwtUtil newUtil(long expirationMs) {
        return new JwtUtil(SECRET, expirationMs);
    }

    @Test
    void tokenRoundTripsSubjectAndIsValid() {
        JwtUtil jwtUtil = newUtil(60_000);

        String token = jwtUtil.generateToken("alice");

        assertThat(jwtUtil.extractUsername(token)).isEqualTo("alice");
        assertThat(jwtUtil.isTokenValid(token)).isTrue();
    }

    @Test
    void rejectsExpiredToken() throws InterruptedException {
        JwtUtil jwtUtil = newUtil(1);

        String token = jwtUtil.generateToken("alice");
        Thread.sleep(20);

        assertThat(jwtUtil.isTokenValid(token)).isFalse();
    }

    @Test
    void rejectsMalformedToken() {
        JwtUtil jwtUtil = newUtil(60_000);

        assertThat(jwtUtil.isTokenValid("not-a-jwt")).isFalse();
    }

    @Test
    void rejectsTokenTamperedAfterSigning() {
        JwtUtil jwtUtil = newUtil(60_000);
        String token = jwtUtil.generateToken("alice");
        String[] parts = token.split("\\.");
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 1)
                + (parts[1].endsWith("A") ? "B" : "A");
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThat(jwtUtil.isTokenValid(tampered)).isFalse();
    }

    @Test
    void rejectsTokenSignedWithADifferentSecret() {
        JwtUtil signer = newUtil(60_000);
        JwtUtil verifier = new JwtUtil("a-completely-different-signing-secret-32-bytes+", 60_000);

        String token = signer.generateToken("alice");

        assertThat(verifier.isTokenValid(token)).isFalse();
    }
}
