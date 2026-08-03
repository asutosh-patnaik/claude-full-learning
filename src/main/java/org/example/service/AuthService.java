package org.example.service;

import org.example.model.User;
import org.example.repository.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean authenticate(String username, String rawPassword) {
        return userRepository.findByUsername(username)
                .filter(user -> !user.isBlocked())
                .map(user -> passwordEncoder.matches(rawPassword, user.getPassword()))
                .orElse(false);
    }

    /**
     * Relies on the unique index on {@code username} (rather than a find-then-save check)
     * so concurrent registrations for the same username can't both succeed.
     */
    public boolean register(String username, String rawPassword) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        try {
            userRepository.save(user);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * Whether a token for {@code username}, issued at {@code tokenIssuedAt} (millisecond
     * precision — see {@link org.example.util.JwtUtil#extractIssuedAt}), still represents a live
     * session — false if the user is blocked, no longer exists, or the token predates the user's
     * {@code tokenValidAfter} marker (set by {@link #changePassword} / {@link #blockUser}).
     * Called by JwtAuthenticationFilter on every request, which is the necessary trade-off for
     * supporting revocation at all: a purely stateless JWT can never be un-issued.
     */
    public boolean isSessionValid(String username, Instant tokenIssuedAt) {
        return userRepository.findByUsername(username)
                .filter(user -> !user.isBlocked())
                .filter(user -> user.getTokenValidAfter() == null
                        || !tokenIssuedAt.isBefore(user.getTokenValidAfter()))
                .isPresent();
    }

    /**
     * Verifies the current password, then sets the new one and bumps {@code tokenValidAfter} to
     * now — every token issued before this call, on any machine, fails {@link #isSessionValid}
     * on its next request.
     */
    public boolean changePassword(String username, String currentPassword, String newPassword) {
        return userRepository.findByUsername(username)
                .filter(user -> passwordEncoder.matches(currentPassword, user.getPassword()))
                .map(user -> {
                    user.setPassword(passwordEncoder.encode(newPassword));
                    user.setTokenValidAfter(Instant.now());
                    userRepository.save(user);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Marks the user blocked and bumps {@code tokenValidAfter}, so existing sessions are killed
     * immediately rather than merely being unable to obtain new ones. There is no HTTP endpoint
     * for this yet — it's a plain service method, callable once an admin capability exists.
     */
    public boolean blockUser(String username) {
        return userRepository.findByUsername(username)
                .map(user -> {
                    user.setBlocked(true);
                    user.setTokenValidAfter(Instant.now());
                    userRepository.save(user);
                    return true;
                })
                .orElse(false);
    }
}
