package org.example.service;

import org.example.dto.UserDetailsResponse;
import org.example.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Empty when the username (taken from the caller's own JWT, never client-supplied) no longer
     * matches a stored user — e.g. the account was deleted after the token was issued.
     */
    public Optional<UserDetailsResponse> getUserDetails(String username) {
        return userRepository.findByUsername(username)
                .map(user -> new UserDetailsResponse(user.getId(), user.getUsername()));
    }
}
