package org.example.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Date;

/**
 * Signs with the RSA private key (only this service holds it) and verifies with the matching
 * public key. Unlike HMAC, knowing the verification key never lets you forge a token — relevant
 * if a future service ever needs to verify tokens without being trusted to mint them.
 */
@Component
public class JwtUtil {

    private static final String ISSUED_AT_MILLIS_CLAIM = "iatMillis";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final long expirationMs;

    public JwtUtil(@Value("${jwt.private-key}") String privateKeyBase64,
                    @Value("${jwt.public-key}") String publicKeyBase64,
                    @Value("${jwt.expiration-ms}") long expirationMs) {
        this.privateKey = parsePrivateKey(privateKeyBase64);
        this.publicKey = parsePublicKey(publicKeyBase64);
        this.expirationMs = expirationMs;
    }

    public String generateToken(String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                // Standard `iat` is second-precision (JWT numeric dates are whole seconds), which
                // is too coarse for session-invalidation comparisons: a token minted in the same
                // wall-clock second as a password change/block could floor to either side of the
                // invalidation instant depending on sub-second timing, making the check
                // unreliable in both directions. This claim carries full millisecond precision
                // for that comparison specifically; `iat` itself is left alone for spec compliance.
                .claim(ISSUED_AT_MILLIS_CLAIM, now.getTime())
                .expiration(expiry)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    /** Millisecond-precision issued-at, for session-invalidation comparisons. See generateToken. */
    public Instant extractIssuedAt(String token) {
        Claims claims = parseClaims(token);
        Long millis = claims.get(ISSUED_AT_MILLIS_CLAIM, Long.class);
        return millis != null ? Instant.ofEpochMilli(millis) : claims.getIssuedAt().toInstant();
    }

    public boolean isTokenValid(String token) {
        try {
            return parseClaims(token).getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static PrivateKey parsePrivateKey(String base64) {
        try {
            byte[] bytes = Decoders.BASE64.decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid RSA private key configured for jwt.private-key", e);
        }
    }

    private static PublicKey parsePublicKey(String base64) {
        try {
            byte[] bytes = Decoders.BASE64.decode(base64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid RSA public key configured for jwt.public-key", e);
        }
    }
}
