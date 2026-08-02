package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
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
 * Exercises the real HTTP stack (controllers, validation, Spring Security filter chain,
 * rate limiting, JWT issuance) against a real MongoDB, provided by Testcontainers so the
 * suite doesn't depend on any manually-started local Mongo instance.
 *
 * Each test uses its own fake client IP via {@link #fromIp(String)} so the shared, singleton
 * RateLimitFilter buckets (capacity overridden below to keep the rate-limit test fast) never
 * leak between otherwise-unrelated test methods.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ratelimit.capacity=3",
        "ratelimit.refill-seconds=300"
})
class AuthControllerIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO_DB_CONTAINER = new MongoDBContainer("mongo:7");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerThenLoginSucceedsAndReturnsAJwt() throws Exception {
        String username = uniqueUsername("alice");

        mockMvc.perform(post("/register").with(fromIp("10.1.0.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, "password123")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("User registered successfully"));

        mockMvc.perform(post("/login").with(fromIp("10.1.0.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Login successful"))
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void duplicateRegistrationIsRejectedWithConflict() throws Exception {
        String username = uniqueUsername("dup");
        String body = credentials(username, "password123");

        mockMvc.perform(post("/register").with(fromIp("10.1.0.2"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/register").with(fromIp("10.1.0.2"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already exists"));
    }

    @Test
    void registrationRejectsPasswordShorterThanEightCharacters() throws Exception {
        mockMvc.perform(post("/register").with(fromIp("10.1.0.3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(uniqueUsername("short"), "short")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginWithWrongPasswordIsRejectedWithoutLeakingAToken() throws Exception {
        String username = uniqueUsername("wrongpass");
        mockMvc.perform(post("/register").with(fromIp("10.1.0.4"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, "password123")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/login").with(fromIp("10.1.0.4"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, "wrongpassword")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void loginWithUnknownUserIsRejected() throws Exception {
        mockMvc.perform(post("/login").with(fromIp("10.1.0.5"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(uniqueUsername("ghost"), "whatever12")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void unauthenticatedRequestToANonPublicPathIsRejected() throws Exception {
        mockMvc.perform(get("/some-protected-path").with(fromIp("10.1.0.6")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void loginIsRateLimitedPerClientIpAfterCapacityIsExceeded() throws Exception {
        String username = uniqueUsername("ratelimited");
        mockMvc.perform(post("/register").with(fromIp("10.1.0.7"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, "password123")))
                .andExpect(status().isCreated());

        // ratelimit.capacity=3 for this test class (see @TestPropertySource)
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/login").with(fromIp("10.1.0.7"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(credentials(username, "password123")))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/login").with(fromIp("10.1.0.7"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, "password123")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests, please try again later"));
    }

    @Test
    void rateLimitBucketsAreIndependentPerClientIp() throws Exception {
        String username = uniqueUsername("multiclient");
        mockMvc.perform(post("/register").with(fromIp("10.1.0.8"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, "password123")))
                .andExpect(status().isCreated());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/login").with(fromIp("10.1.0.8"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(credentials(username, "password123")))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/login").with(fromIp("10.1.0.8"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, "password123")))
                .andExpect(status().isTooManyRequests());

        // a different client IP hitting the same endpoint/username is unaffected
        mockMvc.perform(post("/login").with(fromIp("10.1.0.9"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, "password123")))
                .andExpect(status().isOk());
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
