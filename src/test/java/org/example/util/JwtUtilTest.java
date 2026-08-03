package org.example.util;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private static final String KEY_ID = "test-key";
    private static final TestRsaKeys.Pair KEYS = TestRsaKeys.generate();

    private JwtUtil newUtil(long expirationMs) {
        return new JwtUtil(TestJwtProperties.singleKey(KEY_ID, KEYS, expirationMs));
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
    void extractIssuedAtHasMillisecondPrecisionNotJustSeconds() {
        // Regression test: the standard `iat` claim is second-precision, which is too coarse to
        // reliably order a token against a session-invalidation Instant.now() when both land in
        // the same wall-clock second (flooring to the second could put issuedAt *before* an
        // invalidation instant it should be after). A second-truncated implementation would floor
        // down to the start of the current second — almost always earlier than beforeMillis — so
        // bracketing tightly between before/after millis catches that regression without any
        // sleep-based flakiness.
        JwtUtil jwtUtil = newUtil(60_000);
        long beforeMillis = System.currentTimeMillis();

        String token = jwtUtil.generateToken("alice");

        long afterMillis = System.currentTimeMillis();
        long issuedAtMillis = jwtUtil.extractIssuedAt(token).toEpochMilli();
        assertThat(issuedAtMillis).isBetween(beforeMillis, afterMillis);
    }

    @Test
    void rejectsTokenSignedWithADifferentKeyPair() {
        JwtUtil signer = newUtil(60_000);
        TestRsaKeys.Pair otherKeys = TestRsaKeys.generate();
        JwtUtil verifier = new JwtUtil(TestJwtProperties.singleKey(KEY_ID, otherKeys, 60_000));

        String token = signer.generateToken("alice");

        assertThat(verifier.isTokenValid(token)).isFalse();
    }

    @Test
    void newTokensAreSignedWithTheActiveKeyId() {
        JwtUtil jwtUtil = newUtil(60_000);

        String token = jwtUtil.generateToken("alice");

        String[] parts = token.split("\\.");
        String header = new String(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header).contains("\"kid\":\"" + KEY_ID + "\"");
    }

    @Test
    void tokenSignedByAKeyThatIsStillKnownButNoLongerActiveIsStillAccepted() {
        // Simulates rotation: a token minted while "old-key" was active must keep validating
        // once the active key becomes "new-key", as long as old-key is kept around to verify with.
        TestRsaKeys.Pair oldKeys = TestRsaKeys.generate();
        TestRsaKeys.Pair newKeys = TestRsaKeys.generate();
        JwtUtil beforeRotation = new JwtUtil(TestJwtProperties.singleKey("old-key", oldKeys, 60_000));
        String tokenFromBeforeRotation = beforeRotation.generateToken("alice");

        JwtUtil afterRotation = new JwtUtil(
                TestJwtProperties.withRetiredKey("new-key", newKeys, "old-key", oldKeys, 60_000));

        assertThat(afterRotation.isTokenValid(tokenFromBeforeRotation)).isTrue();
        assertThat(afterRotation.extractUsername(tokenFromBeforeRotation)).isEqualTo("alice");
    }

    @Test
    void tokensMintedAfterRotationUseTheNewKeyNotTheRetiredOne() {
        TestRsaKeys.Pair oldKeys = TestRsaKeys.generate();
        TestRsaKeys.Pair newKeys = TestRsaKeys.generate();
        JwtUtil afterRotation = new JwtUtil(
                TestJwtProperties.withRetiredKey("new-key", newKeys, "old-key", oldKeys, 60_000));

        String token = afterRotation.generateToken("alice");

        JwtUtil onlyKnowsOldKey = new JwtUtil(TestJwtProperties.singleKey("old-key", oldKeys, 60_000));
        assertThat(onlyKnowsOldKey.isTokenValid(token)).isFalse();

        JwtUtil onlyKnowsNewKey = new JwtUtil(TestJwtProperties.singleKey("new-key", newKeys, 60_000));
        assertThat(onlyKnowsNewKey.isTokenValid(token)).isTrue();
    }

    @Test
    void tokenSignedByAFullyRetiredKeyIsRejectedOnceThatKeyIsRemovedFromConfig() {
        TestRsaKeys.Pair oldKeys = TestRsaKeys.generate();
        TestRsaKeys.Pair newKeys = TestRsaKeys.generate();
        JwtUtil beforeRotation = new JwtUtil(TestJwtProperties.singleKey("old-key", oldKeys, 60_000));
        String tokenFromOldKey = beforeRotation.generateToken("alice");

        // old-key has since been fully retired: no longer present in config at all, not even
        // for verification.
        JwtUtil afterFullRetirement = new JwtUtil(TestJwtProperties.singleKey("new-key", newKeys, 60_000));

        assertThat(afterFullRetirement.isTokenValid(tokenFromOldKey)).isFalse();
    }
}
