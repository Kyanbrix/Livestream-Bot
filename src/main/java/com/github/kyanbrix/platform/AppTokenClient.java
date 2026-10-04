package com.github.kyanbrix.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Shared HTTP plumbing for platforms that use the OAuth2 client-credentials flow
 * (both Twitch and Kick). Caches the app access token and refreshes it on expiry or a 401.
 */
public abstract class AppTokenClient {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    // One client for all platforms: a single selector thread and connection pool.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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

    protected static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Builds a repeated query parameter, e.g. {@code slug=a&slug=b}.
     */
    protected static String query(String name, List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(name).append('=').append(encode(value));
        }
        return sb.toString();
    }

    protected static List<List<String>> batches(Collection<String> values, int size) {
        List<String> list = new ArrayList<>(values);
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            batches.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return batches;
    }

    protected static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
