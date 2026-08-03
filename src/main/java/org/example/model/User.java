package org.example.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "users")
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String username;

    /** BCrypt hash — never store or compare plaintext passwords. */
    private String password;

    private boolean blocked;

    /**
     * Tokens with an {@code iat} before this instant are rejected regardless of their own
     * expiry, even if otherwise validly signed and unexpired. Null means no restriction. Bumped
     * to "now" on password change or block, which is how every previously issued token — on any
     * machine — gets invalidated at once without an explicit revocation list.
     */
    private Instant tokenValidAfter;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public Instant getTokenValidAfter() {
        return tokenValidAfter;
    }

    public void setTokenValidAfter(Instant tokenValidAfter) {
        this.tokenValidAfter = tokenValidAfter;
    }
}
