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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void authenticateFailsWhenUserNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(authService.authenticate("ghost", "whatever")).isFalse();
        verifyNoInteractions(passwordEncoder);
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
}
