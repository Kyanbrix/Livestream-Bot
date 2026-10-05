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
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * HTTP and parsing helpers shared by every platform provider.
 */
public abstract class HttpSupport {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    // One client for all platforms: a single selector thread and connection pool.
    protected static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Pattern LOGIN = Pattern.compile("[a-z0-9_-]{2,25}");

    /**
     * GET without authentication, returning the body as text.
     */
    protected static String getPublicText(String url, String userAgent, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", accept)
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new HttpStatusException(url, response.statusCode(), response.body());
        }
        return response.body();
    }

    /**
     * GET without authentication, for public website endpoints and key-authenticated APIs.
     */
    protected static JsonNode getPublicJson(String url, String userAgent) throws IOException, InterruptedException {
        return MAPPER.readTree(getPublicText(url, userAgent, "application/json"));
    }

    /**
     * Normalizes a Twitch/Kick login typed by a user: accepts {@code name}, {@code @name} or a channel URL.
     */
    protected static Optional<String> normalizeLogin(String input) {
        String login = input.trim().toLowerCase();
        login = login.replaceAll("/+$", "");
        login = login.substring(login.lastIndexOf('/') + 1).replaceFirst("^@", "");
        return LOGIN.matcher(login).matches() ? Optional.of(login) : Optional.empty();
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

    protected static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public static class HttpStatusException extends IOException {
        private final int status;

        public HttpStatusException(String url, int status, String body) {
            super("GET " + url + " returned HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body));
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
