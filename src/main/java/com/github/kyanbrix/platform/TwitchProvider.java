package com.github.kyanbrix.platform;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Twitch Helix API: https://dev.twitch.tv/docs/api/reference
 */
public class TwitchProvider extends AppTokenClient implements StreamProvider {

    private static final String API = "https://api.twitch.tv/helix";
    private static final int BATCH_SIZE = 100;
    /** Shown until Twitch has generated a preview for a freshly started stream. */
    public static final String PREVIEW_PLACEHOLDER = "https://static-cdn.jtvnw.net/ttv-static/404_preview-1280x720.jpg";

    public TwitchProvider(String clientId, String clientSecret) {
        super("https://id.twitch.tv/oauth2/token", clientId, clientSecret);
    }

    @Override
    public Platform platform() {
        return Platform.TWITCH;
    }

    @Override
    protected void decorate(HttpRequest.Builder builder) {
        builder.header("Client-Id", clientId);
    }

    @Override
    public Map<String, StreamInfo> fetchLive(Collection<String> usernames) throws IOException, InterruptedException {
        Map<String, StreamInfo> result = new HashMap<>();
        for (List<String> batch : batches(usernames, BATCH_SIZE)) {
            JsonNode streams = getJson(API + "/streams?" + query("user_login", batch) + "&first=" + BATCH_SIZE).path("data");
            if (streams.isEmpty()) {
                continue;
            }

            List<String> liveLogins = new ArrayList<>();
            List<String> gameIds = new ArrayList<>();
            streams.forEach(s -> {
                liveLogins.add(s.path("user_login").asText().toLowerCase());
                String gameId = s.path("game_id").asText("");
                if (!gameId.isEmpty() && !gameIds.contains(gameId)) {
                    gameIds.add(gameId);
                }
            });
            Map<String, String> avatars = fetchAvatars(liveLogins);
            Map<String, String> boxArt = fetchBoxArt(gameIds);

            long cacheBuster = Instant.now().getEpochSecond();
            for (JsonNode s : streams) {
                if (!"live".equals(s.path("type").asText())) {
                    continue;
                }
                String login = s.path("user_login").asText().toLowerCase();
                String thumbnailUrl = s.path("thumbnail_url").asText("");
                String thumbnail = thumbnailUrl.isBlank()
                        ? PREVIEW_PLACEHOLDER
                        : thumbnailUrl.replace("{width}", "1280").replace("{height}", "720") + "?t=" + cacheBuster;
                result.put(login, new StreamInfo(
                        Platform.TWITCH,
                        login,
                        s.path("user_name").asText(login),
                        s.path("title").asText(""),
                        s.path("game_name").asText(""),
                        s.path("viewer_count").asInt(),
                        thumbnail,
                        avatars.get(login),
                        boxArt.get(s.path("game_id").asText("")),
                        parseInstant(s.path("started_at").asText(null)),
                        null
                ));
            }
        }
        return result;
    }

    @Override
    public Optional<Vod> findVod(String username, Instant startedAt) throws IOException, InterruptedException {
        JsonNode user = getJson(API + "/users?login=" + encode(username)).path("data").path(0);
        if (user.isMissingNode()) {
            return Optional.empty();
        }
        JsonNode videos = getJson(API + "/videos?user_id=" + encode(user.path("id").asText()) + "&type=archive&first=5").path("data");
        for (JsonNode v : videos) {
            if (StreamProvider.sameBroadcast(parseInstant(v.path("created_at").asText(null)), startedAt)) {
                String thumbnail = v.path("thumbnail_url").asText("");
                // Blank while Twitch is still processing the recording.
                return Optional.of(new Vod(v.path("url").asText(),
                        thumbnail.isBlank() ? null : thumbnail.replace("%{width}", "1280").replace("%{height}", "720")));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Channel> resolve(String input) throws IOException, InterruptedException {
        Optional<String> login = normalizeLogin(input);
        if (login.isEmpty()) {
            return Optional.empty();
        }
        JsonNode user = getJson(API + "/users?login=" + encode(login.get())).path("data").path(0);
        return user.isMissingNode()
                ? Optional.empty()
                : Optional.of(new Channel(login.get(), user.path("display_name").asText(login.get())));
    }

    private Map<String, String> fetchBoxArt(List<String> gameIds) throws IOException, InterruptedException {
        Map<String, String> boxArt = new HashMap<>();
        if (gameIds.isEmpty()) {
            return boxArt;
        }
        JsonNode games = getJson(API + "/games?" + query("id", gameIds)).path("data");
        games.forEach(g -> boxArt.put(g.path("id").asText(), g.path("box_art_url").asText("")
                .replace("{width}", "285")
                .replace("{height}", "380")));
        return boxArt;
    }

    private Map<String, String> fetchAvatars(List<String> logins) throws IOException, InterruptedException {
        Map<String, String> avatars = new HashMap<>();
        JsonNode users = getJson(API + "/users?" + query("login", logins)).path("data");
        users.forEach(u -> avatars.put(u.path("login").asText().toLowerCase(), u.path("profile_image_url").asText(null)));
        return avatars;
    }
}
