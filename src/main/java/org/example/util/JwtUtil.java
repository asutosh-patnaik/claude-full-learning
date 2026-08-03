package org.example.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.Decoders;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Signs with the active RSA private key (only this service holds it) and verifies with whichever
 * known public key matches the token's `kid` header. Unlike HMAC, knowing a verification key
 * never lets you forge a token — relevant if a future service ever needs to verify tokens without
 * being trusted to mint them.
 *
 * Supports key rotation: every known key's public half stays available for verification, but only
 * the one matching jwt.active-key-id is used to sign new tokens. To rotate, add a new key entry,
 * point active-key-id at it, and keep the previous entry (public key only, private key can be
 * discarded) around until its already-issued tokens would have naturally expired anyway — only
 * then remove it, at which point tokens signed with it are rejected as an unknown key.
 */
@Component
public class JwtUtil {

    private static final String ISSUED_AT_MILLIS_CLAIM = "iatMillis";

    private final String activeKeyId;
    private final PrivateKey activePrivateKey;
    private final Map<String, PublicKey> publicKeysById;
    private final long expirationMs;

    public JwtUtil(JwtProperties properties) {
        this.expirationMs = properties.getExpirationMs();
        this.activeKeyId = properties.getActiveKeyId();

        Map<String, PublicKey> keys = new HashMap<>();
        PrivateKey signingKey = null;
        for (JwtProperties.KeyEntry entry : properties.getKeys()) {
            keys.put(entry.getId(), parsePublicKey(entry.getPublicKey()));
            if (entry.getId().equals(activeKeyId)) {
                if (entry.getPrivateKey() == null) {
                    throw new IllegalStateException(
                            "jwt.active-key-id '" + activeKeyId + "' has no private-key configured");
                }
                signingKey = parsePrivateKey(entry.getPrivateKey());
            }
        }
        if (signingKey == null) {
            throw new IllegalStateException(
                    "No jwt.keys entry found with id matching jwt.active-key-id=" + activeKeyId);
        }
        this.activePrivateKey = signingKey;
        this.publicKeysById = Map.copyOf(keys);
    }

    public String generateToken(String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .header().keyId(activeKeyId).and()
                .subject(username)
                .issuedAt(now)
                // Standard `iat` is second-precision (JWT numeric dates are whole seconds), which
                // is too coarse for session-invalidation comparisons: a token minted in the same
                // wall-clock second as a password change/block could floor to either side of the
                // invalidation instant depending on sub-second timing. This claim carries full
                // millisecond precision for that comparison specifically; `iat` itself is left
                // alone for spec compliance.
                .claim(ISSUED_AT_MILLIS_CLAIM, now.getTime())
                .expiration(expiry)
                .signWith(activePrivateKey, Jwts.SIG.RS256)
                .compact();
    }

    public String extractUsername(String token) {
        return extractUsername(parseClaims(token));
    }

    public String extractUsername(Claims claims) {
        return claims.getSubject();
    }

    /** Millisecond-precision issued-at, for session-invalidation comparisons. See generateToken. */
    public Instant extractIssuedAt(String token) {
        return extractIssuedAt(parseClaims(token));
    }

    /** Millisecond-precision issued-at, for session-invalidation comparisons. See generateToken. */
    public Instant extractIssuedAt(Claims claims) {
        Long millis = claims.get(ISSUED_AT_MILLIS_CLAIM, Long.class);
        return millis != null ? Instant.ofEpochMilli(millis) : claims.getIssuedAt().toInstant();
    }

    public boolean isTokenValid(String token) {
        return parseIfValid(token).isPresent();
    }

    /**
     * Parses and signature-verifies the token exactly once, returning its claims iff it's
     * cryptographically valid and unexpired (empty otherwise). Callers that need more than one
     * claim off the same token — e.g. subject and issued-at — should use this instead of the
     * single-claim {@code extract*(String)} methods, each of which does its own full parse; a
     * request path pulling multiple claims that way would re-verify the RSA signature once per
     * claim instead of once per token.
     */
    public Optional<Claims> parseIfValid(String token) {
        try {
            Claims claims = parseClaims(token);
            return claims.getExpiration().after(new Date()) ? Optional.of(claims) : Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .keyLocator(header -> {
                    String kid = String.valueOf(header.get("kid"));
                    PublicKey key = publicKeysById.get(kid);
                    if (key == null) {
                        throw new UnsupportedJwtException("Unknown JWT key id: " + kid);
                    }
                    return (Key) key;
                })
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static PrivateKey parsePrivateKey(String base64) {
        try {
            byte[] bytes = Decoders.BASE64.decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid RSA private key in jwt.keys configuration", e);
        }
    }

    private static PublicKey parsePublicKey(String base64) {
        try {
            byte[] bytes = Decoders.BASE64.decode(base64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid RSA public key in jwt.keys configuration", e);
        }
    }
}
