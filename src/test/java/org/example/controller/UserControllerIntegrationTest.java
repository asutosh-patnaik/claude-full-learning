package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.AuthService;
import org.example.util.JwtProperties;
import org.example.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises GET /users/me end to end: real JWTs minted by /login, real Spring Security enforcement
 * (not just the JwtAuthenticationFilter/JwtUtil unit tests), against a real MongoDB via Testcontainers.
 *
 * As in AuthControllerIntegrationTest, each test uses its own fake client IP so the /register and
 * /login calls needed to obtain a token don't drain a shared RateLimitFilter bucket across tests.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO_DB_CONTAINER = new MongoDBContainer("mongo:7");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void validTokenReturnsTheCallersOwnDetails() throws Exception {
        String username = uniqueUsername("alice");
        String token = registerAndLogin("10.2.0.1", username, "password123");

        mockMvc.perform(get("/users/me").with(fromIp("10.2.0.1"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        mockMvc.perform(get("/users/me").with(fromIp("10.2.0.2")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        mockMvc.perform(get("/users/me").with(fromIp("10.2.0.3"))
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String username = uniqueUsername("expiring");
        registerAndLogin("10.2.0.4", username, "password123");

        // Signed with the app's real key(s) so it's otherwise indistinguishable from a genuine
        // token, but built with a JwtUtil configured for near-instant expiry.
        JwtProperties quicklyExpiringProperties = new JwtProperties();
        quicklyExpiringProperties.setActiveKeyId(jwtProperties.getActiveKeyId());
        quicklyExpiringProperties.setKeys(jwtProperties.getKeys());
        quicklyExpiringProperties.setExpirationMs(1);
        JwtUtil quicklyExpiring = new JwtUtil(quicklyExpiringProperties);
        String expiredToken = quicklyExpiring.generateToken(username);
        Thread.sleep(20);

        mockMvc.perform(get("/users/me").with(fromIp("10.2.0.4"))
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void eachUserOnlyEverSeesTheirOwnDetails() throws Exception {
        String usernameA = uniqueUsername("userA");
        String usernameB = uniqueUsername("userB");
        String tokenA = registerAndLogin("10.2.0.5", usernameA, "password123");
        String tokenB = registerAndLogin("10.2.0.6", usernameB, "password123");

        mockMvc.perform(get("/users/me").with(fromIp("10.2.0.5"))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(usernameA));

        mockMvc.perform(get("/users/me").with(fromIp("10.2.0.6"))
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(usernameB));
    }

    @Test
    void changingPasswordInvalidatesEveryPreviouslyIssuedToken() throws Exception {
        String username = uniqueUsername("rotating");
        String oldToken = registerAndLogin("10.2.0.7", username, "password123");

        mockMvc.perform(post("/users/me/password").with(fromIp("10.2.0.7"))
                        .header("Authorization", "Bearer " + oldToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("password123", "newpassword456")))
                .andExpect(status().isOk());

        // the token obtained before the password change is dead now, on this "machine" or any other
        mockMvc.perform(get("/users/me").with(fromIp("10.2.0.7"))
                        .header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());

        // logging in again with the new password gets a fresh token that works fine
        String newToken = login("10.2.0.7", username, "newpassword456");
        mockMvc.perform(get("/users/me").with(fromIp("10.2.0.7"))
                        .header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void changePasswordWithWrongCurrentPasswordFailsAndLeavesTheExistingSessionIntact() throws Exception {
        String username = uniqueUsername("wrongcurrent");
        String token = registerAndLogin("10.2.0.8", username, "password123");

        mockMvc.perform(post("/users/me/password").with(fromIp("10.2.0.8"))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("totally-wrong", "newpassword456")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));

        // the existing session was never touched, since the change was rejected
        mockMvc.perform(get("/users/me").with(fromIp("10.2.0.8"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void blockingAUserInvalidatesEveryPreviouslyIssuedTokenImmediately() throws Exception {
        // There's no admin HTTP endpoint for blocking yet, so this simulates the trigger by
        // calling the real Spring-managed AuthService bean directly — everything downstream
        // (persistence, JwtAuthenticationFilter's DB check) is still exercised for real.
        String username = uniqueUsername("blockme");
        String token = registerAndLogin("10.2.0.9", username, "password123");

        mockMvc.perform(get("/users/me").with(fromIp("10.2.0.9"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        boolean blocked = authService.blockUser(username);
        assertThat(blocked).isTrue();

        mockMvc.perform(get("/users/me").with(fromIp("10.2.0.9"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blockedUserCannotLogInToObtainANewTokenEither() throws Exception {
        String username = uniqueUsername("blockedlogin");
        registerAndLogin("10.2.0.10", username, "password123");

        assertThat(authService.blockUser(username)).isTrue();

        mockMvc.perform(post("/login").with(fromIp("10.2.0.10"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, "password123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    private String registerAndLogin(String ip, String username, String password) throws Exception {
        mockMvc.perform(post("/register").with(fromIp(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, password)))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(post("/login").with(fromIp(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("token").asText();
    }

    private String login(String ip, String username, String password) throws Exception {
        String body = mockMvc.perform(post("/login").with(fromIp(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("token").asText();
    }

    private String changePasswordBody(String currentPassword, String newPassword) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("currentPassword", currentPassword);
        body.put("newPassword", newPassword);
        return objectMapper.writeValueAsString(body);
    }

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String uniqueUsername(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private String credentials(String username, String password) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return objectMapper.writeValueAsString(body);
    }
}
