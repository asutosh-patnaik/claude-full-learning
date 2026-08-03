package org.example.service;

import org.example.model.User;
import org.example.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder);
    }

    @Test
    void authenticateSucceedsWhenPasswordMatches() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword("hashed");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("plain", "hashed")).thenReturn(true);

        assertThat(authService.authenticate("alice", "plain")).isTrue();
    }

    @Test
    void authenticateFailsWhenPasswordDoesNotMatch() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword("hashed");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThat(authService.authenticate("alice", "wrong")).isFalse();
    }

    @Test
    void authenticateFailsWhenUserIsBlockedEvenWithCorrectPassword() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword("hashed");
        user.setBlocked(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("plain", "hashed")).thenReturn(true);

        assertThat(authService.authenticate("alice", "plain")).isFalse();
    }

    @Test
    void authenticateFailsWhenUserNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(authService.authenticate("ghost", "whatever")).isFalse();
    }

    @Test
    void authenticateChecksAConstantDummyHashWhenUsernameDoesNotExist() {
        // Regression test for a timing side-channel: if a missing username short-circuited
        // without calling passwordEncoder.matches, response time would differ from an existing
        // username and let an attacker enumerate valid accounts by timing alone.
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        authService.authenticate("ghost", "whatever");

        verify(passwordEncoder).matches(eq("whatever"), any());
    }

    @Test
    void registerEncodesPasswordAndSavesUser() {
        when(passwordEncoder.encode("plain")).thenReturn("hashed");

        boolean result = authService.register("alice", "plain");

        assertThat(result).isTrue();
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("alice");
        assertThat(captor.getValue().getPassword()).isEqualTo("hashed");
    }

    @Test
    void registerFailsOnDuplicateUsername() {
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThat(authService.register("alice", "plain")).isFalse();
    }

    @Test
    void sessionIsValidWhenUserHasNoInvalidationMarker() {
        User user = new User();
        user.setUsername("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThat(authService.isSessionValid("alice", Instant.now())).isTrue();
    }

    @Test
    void sessionIsValidWhenTokenWasIssuedAfterInvalidationMarker() {
        User user = new User();
        user.setUsername("alice");
        user.setTokenValidAfter(Instant.now().minus(1, ChronoUnit.HOURS));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThat(authService.isSessionValid("alice", Instant.now())).isTrue();
    }

    @Test
    void sessionIsInvalidWhenTokenPredatesInvalidationMarker() {
        User user = new User();
        user.setUsername("alice");
        user.setTokenValidAfter(Instant.now());
        Instant tokenIssuedBeforeInvalidation = Instant.now().minus(1, ChronoUnit.HOURS);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThat(authService.isSessionValid("alice", tokenIssuedBeforeInvalidation)).isFalse();
    }

    @Test
    void sessionIsInvalidWhenUserIsBlockedRegardlessOfTokenAge() {
        User user = new User();
        user.setUsername("alice");
        user.setBlocked(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThat(authService.isSessionValid("alice", Instant.now())).isFalse();
    }

    @Test
    void sessionIsInvalidWhenUserNoLongerExists() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(authService.isSessionValid("ghost", Instant.now())).isFalse();
    }

    @Test
    void sessionValidityIsCorrectEvenWhenTokenAndInvalidationLandInTheSameWallClockSecond() {
        // Regression test for the truncation bug: comparing at second granularity could make a
        // token issued a few milliseconds before the invalidation marker look like it came after,
        // or vice versa, whenever both timestamps fall in the same second.
        User user = new User();
        user.setUsername("alice");
        Instant invalidatedAt = Instant.now();
        user.setTokenValidAfter(invalidatedAt);
        Instant tokenIssuedOneMillisecondEarlier = invalidatedAt.minusMillis(1);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThat(authService.isSessionValid("alice", tokenIssuedOneMillisecondEarlier)).isFalse();
    }

    @Test
    void changePasswordUpdatesHashAndBumpsInvalidationMarkerWhenCurrentPasswordMatches() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword("old-hash");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old-plain", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-plain")).thenReturn("new-hash");
        Instant before = Instant.now();

        boolean result = authService.changePassword("alice", "old-plain", "new-plain");

        assertThat(result).isTrue();
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("new-hash");
        assertThat(captor.getValue().getTokenValidAfter()).isNotNull().isAfterOrEqualTo(before);
    }

    @Test
    void changePasswordFailsWhenCurrentPasswordIsWrongAndDoesNotTouchTheAccount() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword("old-hash");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "old-hash")).thenReturn(false);

        assertThat(authService.changePassword("alice", "wrong", "new-plain")).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    void blockUserSetsBlockedFlagAndBumpsInvalidationMarker() {
        User user = new User();
        user.setUsername("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        Instant before = Instant.now();

        boolean result = authService.blockUser("alice");

        assertThat(result).isTrue();
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().isBlocked()).isTrue();
        assertThat(captor.getValue().getTokenValidAfter()).isNotNull().isAfterOrEqualTo(before);
    }

    @Test
    void blockUserFailsWhenUserDoesNotExist() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(authService.blockUser("ghost")).isFalse();
    }
}
