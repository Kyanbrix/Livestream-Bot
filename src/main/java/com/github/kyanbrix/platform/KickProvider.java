package com.github.kyanbrix.platform;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Kick public API: https://docs.kick.com
 */
public class KickProvider extends AppTokenClient implements StreamProvider {

    private static final String API = "https://api.kick.com/public/v1";
    private static final int BATCH_SIZE = 50;
    private static final DateTimeFormatter VOD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36";

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
                String categoryImage = blankToNull(c.path("category").path("thumbnail").asText(null));
                // Prefer the live preview (upgraded to 720p); before Kick has one, fall back to the channel banner.
                String thumbnail = blankToNull(stream.path("thumbnail").asText(null));
                thumbnail = thumbnail != null
                        ? thumbnail.replaceFirst("/480\\.webp$", "/720.webp") + "?t=" + cacheBuster
                        : Objects.requireNonNullElse(blankToNull(c.path("banner_picture").asText(null)), categoryImage);
                result.put(slug, new StreamInfo(
                        Platform.KICK,
                        slug,
                        user != null ? user.path("name").asText(slug) : slug,
                        c.path("stream_title").asText(""),
                        c.path("category").path("name").asText(""),
                        stream.path("viewer_count").asInt(),
                        thumbnail,
                        user != null ? user.path("profile_picture").asText(null) : null,
                        categoryImage,
                        parseInstant(stream.path("start_time").asText(null)),
                        null
                ));
            }
        }
        return result;
    }

    /**
     * Kick's public API has no VOD endpoint, so this uses the website's own (unofficial) one.
     * It can change or be blocked at any time, so any failure just means "no VOD".
     */
    @Override
    public Optional<Vod> findVod(String username, Instant startedAt) {
        try {
            JsonNode videos = getPublicJson("https://kick.com/api/v2/channels/" + encode(username) + "/videos", BROWSER_UA);
            for (JsonNode v : videos) {
                Instant start = LocalDateTime.parse(v.path("start_time").asText(), VOD_TIME).toInstant(ZoneOffset.UTC);
                String uuid = v.path("video").path("uuid").asText("");
                if (!uuid.isEmpty() && !v.path("is_live").asBoolean(false) && StreamProvider.sameBroadcast(start, startedAt)) {
                    return Optional.of(new Vod("https://kick.com/" + username + "/videos/" + uuid,
                            blankToNull(v.path("thumbnail").path("src").asText(null))));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // best-effort, see above
        }
        return Optional.empty();
    }

    @Override
    public Optional<Channel> resolve(String input) throws IOException, InterruptedException {
        Optional<String> slug = normalizeLogin(input);
        if (slug.isEmpty() || getJson(API + "/channels?slug=" + encode(slug.get())).path("data").isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Channel(slug.get(), slug.get()));
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
