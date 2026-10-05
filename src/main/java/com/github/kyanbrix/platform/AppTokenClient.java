package com.github.kyanbrix.platform;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Base for platforms that use the OAuth2 client-credentials flow (both Twitch and Kick).
 * Caches the app access token and refreshes it on expiry or a 401.
 */
public abstract class AppTokenClient extends HttpSupport {

    private final String tokenUrl;
    protected final String clientId;
    private final String clientSecret;

    private String accessToken;
    private Instant tokenExpiry = Instant.EPOCH;

    protected AppTokenClient(String tokenUrl, String clientId, String clientSecret) {
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * Adds platform-specific headers (besides Authorization) to an API request.
     */
    protected void decorate(HttpRequest.Builder builder) {
    }

    protected JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = send(url);
        if (response.statusCode() == 401) {
            invalidateToken();
            response = send(url);
        }
        if (response.statusCode() != 200) {
            throw new IOException("GET " + url + " returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> send(String url) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token())
                .header("Accept", "application/json")
                .GET();
        decorate(builder);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private synchronized void invalidateToken() {
        accessToken = null;
        tokenExpiry = Instant.EPOCH;
    }

    private synchronized String token() throws IOException, InterruptedException {
        if (accessToken != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }
        String body = "client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&grant_type=client_credentials";
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Token request to " + tokenUrl + " failed with HTTP "
                    + response.statusCode() + ": " + response.body());
        }
        JsonNode json = MAPPER.readTree(response.body());
        accessToken = json.path("access_token").asText();
        // Kick returns expires_in as a string, Twitch as a number; asLong handles both.
        long expiresIn = json.path("expires_in").asLong(3600);
        // Refresh a minute early to avoid using a token right as it expires.
        tokenExpiry = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
        return accessToken;
    }
}
