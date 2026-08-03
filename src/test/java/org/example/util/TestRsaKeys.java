package org.example.util;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Generates fresh, throwaway RSA key pairs for tests, base64-encoded the way JwtUtil expects. */
public final class TestRsaKeys {

    private TestRsaKeys() {
    }

    public record Pair(String privateKeyBase64, String publicKeyBase64) {
    }

    public static Pair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new Pair(
                    Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
