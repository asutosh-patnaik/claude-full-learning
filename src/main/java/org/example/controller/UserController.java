package org.example.controller;

import jakarta.validation.Valid;
import org.example.dto.ChangePasswordRequest;
import org.example.dto.MessageResponse;
import org.example.dto.UserDetailsResponse;
import org.example.service.AuthService;
import org.example.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    public UserController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
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

    @PostMapping("/users/me/password")
    public ResponseEntity<MessageResponse> changePassword(@AuthenticationPrincipal String username,
                                                            @Valid @RequestBody ChangePasswordRequest request) {
        if (authService.changePassword(username, request.getCurrentPassword(), request.getNewPassword())) {
            return ResponseEntity.ok(new MessageResponse(
                    "Password changed successfully. All existing sessions have been signed out."));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new MessageResponse("Current password is incorrect"));
    }
}
