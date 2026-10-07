package com.ledgerlab.auth.api;

public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds, MeResponse session) {

    @Override
    public String toString() {
        return "LoginResponse[accessToken=<redacted>, expiresInSeconds=" + expiresInSeconds + "]";
    }
}
