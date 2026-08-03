package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${jwt.private-key}")
    private String jwtPrivateKey;

    @Value("${jwt.public-key}")
    private String jwtPublicKey;

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

        // Signed with the app's real key pair so it's otherwise indistinguishable from a genuine
        // token, but built with a JwtUtil configured for near-instant expiry.
        JwtUtil quicklyExpiring = new JwtUtil(jwtPrivateKey, jwtPublicKey, 1);
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
