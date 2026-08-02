package org.example.service;

import org.example.dto.UserDetailsResponse;
import org.example.model.User;
import org.example.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository);
    }

    @Test
    void returnsDetailsWithoutPasswordWhenUserExists() {
        User user = new User();
        user.setId("abc123");
        user.setUsername("alice");
        user.setPassword("bcrypt-hash-should-never-leave-this-method");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        Optional<UserDetailsResponse> result = userService.getUserDetails("alice");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("abc123");
        assertThat(result.get().getUsername()).isEqualTo("alice");
    }

    @Test
    void returnsEmptyWhenUserNoLongerExists() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(userService.getUserDetails("ghost")).isEmpty();
    }
}
