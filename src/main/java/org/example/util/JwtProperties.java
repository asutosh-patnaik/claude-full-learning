package org.example.util;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Backs jwt.* properties, including a list of known signing keys rather than a single pair, so a
 * key rotation can add a new active key while keeping the previous one around long enough to
 * verify tokens it already signed. See JwtUtil for how activeKeyId/keys are actually used.
 */
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String activeKeyId;
    private long expirationMs;
    private List<KeyEntry> keys = new ArrayList<>();

    public String getActiveKeyId() {
        return activeKeyId;
    }

    public void setActiveKeyId(String activeKeyId) {
        this.activeKeyId = activeKeyId;
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public void setExpirationMs(long expirationMs) {
        this.expirationMs = expirationMs;
    }

    public List<KeyEntry> getKeys() {
        return keys;
    }

    public void setKeys(List<KeyEntry> keys) {
        this.keys = keys;
    }

    /** privateKey is only required for the entry whose id matches activeKeyId. */
    public static class KeyEntry {
        private String id;
        private String publicKey;
        private String privateKey;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }
    }
}
