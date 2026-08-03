package org.example.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private static final TestRsaKeys.Pair KEYS = TestRsaKeys.generate();

    private JwtUtil newUtil(long expirationMs) {
        return new JwtUtil(KEYS.privateKeyBase64(), KEYS.publicKeyBase64(), expirationMs);
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
    void rejectsTokenSignedWithADifferentKeyPair() {
        JwtUtil signer = newUtil(60_000);
        TestRsaKeys.Pair otherKeys = TestRsaKeys.generate();
        JwtUtil verifier = new JwtUtil(otherKeys.privateKeyBase64(), otherKeys.publicKeyBase64(), 60_000);

        String token = signer.generateToken("alice");

        assertThat(verifier.isTokenValid(token)).isFalse();
    }
}
