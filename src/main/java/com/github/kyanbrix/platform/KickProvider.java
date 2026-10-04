package com.github.kyanbrix.platform;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kick public API: https://docs.kick.com
 */
public class KickProvider extends AppTokenClient implements StreamProvider {

    private static final String API = "https://api.kick.com/public/v1";
    private static final int BATCH_SIZE = 50;

    public KickProvider(String clientId, String clientSecret) {
        super("https://id.kick.com/oauth/token", clientId, clientSecret);
    }

    @Override
    public Platform platform() {
        return Platform.KICK;
    }

    @Override
    public Map<String, StreamInfo> fetchLive(Collection<String> usernames) throws IOException, InterruptedException {
        Map<String, StreamInfo> result = new HashMap<>();
        for (List<String> batch : batches(usernames, BATCH_SIZE)) {
            JsonNode channels = getJson(API + "/channels?" + query("slug", batch)).path("data");

            List<JsonNode> live = new ArrayList<>();
            channels.forEach(c -> {
                if (c.path("stream").path("is_live").asBoolean(false)) {
                    live.add(c);
                }
            });
            if (live.isEmpty()) {
                continue;
            }

            List<String> userIds = live.stream().map(c -> c.path("broadcaster_user_id").asText()).toList();
            Map<String, JsonNode> users = fetchUsers(userIds);

            long cacheBuster = Instant.now().getEpochSecond();
            for (JsonNode c : live) {
                String slug = c.path("slug").asText().toLowerCase();
                JsonNode stream = c.path("stream");
                JsonNode user = users.get(c.path("broadcaster_user_id").asText());
                String thumbnail = stream.path("thumbnail").asText(null);
                result.put(slug, new StreamInfo(
                        Platform.KICK,
                        slug,
                        user != null ? user.path("name").asText(slug) : slug,
                        c.path("stream_title").asText(""),
                        c.path("category").path("name").asText(""),
                        stream.path("viewer_count").asInt(),
                        thumbnail == null || thumbnail.isBlank() ? null : thumbnail + "?t=" + cacheBuster,
                        user != null ? user.path("profile_picture").asText(null) : null,
                        parseInstant(stream.path("start_time").asText(null))
                ));
            }
        }
        return result;
    }

    @Override
    public boolean exists(String username) throws IOException, InterruptedException {
        return !getJson(API + "/channels?slug=" + encode(username)).path("data").isEmpty();
    }

    private Map<String, JsonNode> fetchUsers(List<String> userIds) throws IOException, InterruptedException {
        Map<String, JsonNode> users = new HashMap<>();
        try {
            getJson(API + "/users?" + query("id", userIds)).path("data")
                    .forEach(u -> users.put(u.path("user_id").asText(), u));
        } catch (IOException e) {
            // Avatars and display names are cosmetic; don't fail the whole poll over them.
        }
        return users;
    }
}
