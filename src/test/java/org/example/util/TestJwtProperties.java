package org.example.util;

import java.util.List;

/** Builds JwtProperties instances for tests without needing Spring's property binder. */
public final class TestJwtProperties {

    private TestJwtProperties() {
    }

    public static JwtProperties singleKey(String keyId, TestRsaKeys.Pair keys, long expirationMs) {
        JwtProperties properties = new JwtProperties();
        properties.setActiveKeyId(keyId);
        properties.setExpirationMs(expirationMs);
        properties.setKeys(List.of(fullEntry(keyId, keys)));
        return properties;
    }

    /** Simulates the state right after a rotation: a new active key, plus the previous one (verify-only). */
    public static JwtProperties withRetiredKey(String activeKeyId, TestRsaKeys.Pair activeKeys,
                                                String retiredKeyId, TestRsaKeys.Pair retiredKeys,
                                                long expirationMs) {
        JwtProperties properties = new JwtProperties();
        properties.setActiveKeyId(activeKeyId);
        properties.setExpirationMs(expirationMs);
        properties.setKeys(List.of(
                fullEntry(activeKeyId, activeKeys),
                publicOnlyEntry(retiredKeyId, retiredKeys)));
        return properties;
    }

    private static JwtProperties.KeyEntry fullEntry(String keyId, TestRsaKeys.Pair keys) {
        JwtProperties.KeyEntry entry = new JwtProperties.KeyEntry();
        entry.setId(keyId);
        entry.setPrivateKey(keys.privateKeyBase64());
        entry.setPublicKey(keys.publicKeyBase64());
        return entry;
    }

    private static JwtProperties.KeyEntry publicOnlyEntry(String keyId, TestRsaKeys.Pair keys) {
        JwtProperties.KeyEntry entry = new JwtProperties.KeyEntry();
        entry.setId(keyId);
        entry.setPublicKey(keys.publicKeyBase64());
        return entry;
    }
}
