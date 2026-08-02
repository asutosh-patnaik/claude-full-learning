package org.example.dto;

public class UserDetailsResponse {

    private final String id;
    private final String username;

    public UserDetailsResponse(String id, String username) {
        this.id = id;
        this.username = username;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }
}
