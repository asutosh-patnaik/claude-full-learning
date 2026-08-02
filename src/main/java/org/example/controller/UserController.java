package org.example.controller;

import org.example.dto.MessageResponse;
import org.example.dto.UserDetailsResponse;
import org.example.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // Identity comes only from the caller's own JWT (populated by JwtAuthenticationFilter into the
    // SecurityContext) — there is no path/query parameter for a username, so a caller can never
    // request another user's details. SecurityConfig's anyRequest().authenticated() already blocks
    // unauthenticated/invalid-token requests before this method runs.
    @GetMapping("/users/me")
    public ResponseEntity<?> getCurrentUser(@AuthenticationPrincipal String username) {
        Optional<UserDetailsResponse> details = userService.getUserDetails(username);
        if (details.isPresent()) {
            return ResponseEntity.ok(details.get());
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("User not found"));
    }
}
