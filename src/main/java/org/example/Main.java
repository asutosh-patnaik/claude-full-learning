package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// UserDetailsServiceAutoConfiguration is excluded because this app never uses Spring Security's
// AuthenticationManager/UserDetailsService (auth is custom, via AuthService) — left enabled, it
// creates an unused in-memory user and prints a random generated password on every startup.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
