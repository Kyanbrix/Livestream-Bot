package com.github.kyanbrix.notify;

import com.github.kyanbrix.db.Database;
import com.github.kyanbrix.db.Database.TrackedStreamer;
import com.github.kyanbrix.platform.Platform;
import com.github.kyanbrix.platform.YouTubeProvider;
import com.github.kyanbrix.platform.YouTubeVideo;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Announces new YouTube videos and Shorts. Runs on the {@link StreamPoller} thread after each live check.
 */
class UploadPoller {

    private static final Logger log = LoggerFactory.getLogger(UploadPoller.class);

    /** Older entries are never announced, e.g. an old video made public or a feed reshuffle. */
    private static final Duration MAX_AGE = Duration.ofHours(48);

    private final JDA jda;
    private final Database db;
    private final YouTubeProvider youtube;

    UploadPoller(JDA jda, Database db, YouTubeProvider youtube) {
        this.jda = jda;
        this.db = db;
        this.youtube = youtube;
    }

    void poll(List<TrackedStreamer> tracked) {
        Map<String, List<TrackedStreamer>> byChannel = tracked.stream()
                .filter(s -> s.platform() == Platform.YOUTUBE && (s.notifyVideos() || s.notifyShorts()))
                .collect(Collectors.groupingBy(TrackedStreamer::username, LinkedHashMap::new, Collectors.toList()));
        for (var entry : byChannel.entrySet()) {
            try {
                poll(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("Failed to check YouTube uploads for {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private void poll(String channelId, List<TrackedStreamer> subscribers) throws Exception {
        List<YouTubeVideo> feed = youtube.fetchFeed(channelId);
        List<String> feedIds = feed.stream().map(YouTubeVideo::videoId).toList();

        // First time we see this channel: remember its current videos without announcing the backlog.
        if (!db.hasSeen(channelId)) {
            db.markSeen(channelId, feedIds);
            return;
        }

        Set<String> unseen = db.unseen(channelId, feedIds);
        if (unseen.isEmpty()) {
            return;
        }
        Instant cutoff = Instant.now().minus(MAX_AGE);
        List<YouTubeVideo> fresh = feed.stream()
                .filter(v -> unseen.contains(v.videoId()) && v.published() != null && v.published().isAfter(cutoff))
                .toList();
        // Throws on API errors before anything is marked seen, so the next tick retries.
        List<YouTubeVideo> uploads = fresh.isEmpty() ? List.of() : youtube.classifyUploads(fresh);

        // Mark first: a failed post is better than posting the same video every tick.
        db.markSeen(channelId, unseen);
        db.pruneSeen(channelId, feedIds);

        if (uploads.isEmpty()) {
            return;
        }
        String avatar = youtube.avatar(channelId);
        for (YouTubeVideo video : uploads.reversed()) { // feed is newest first; post in upload order
            for (TrackedStreamer s : subscribers) {
                if (video.isShort() ? !s.notifyShorts() : !s.notifyVideos()) {
                    continue;
                }
                try {
                    PostTarget target = PostTarget.of(jda, db.getSettings(s.guildId()), s);
                    if (target == null) {
                        continue;
                    }
                    target.channel().sendMessage(EmbedFactory.uploadMessage(video, avatar, target.pingRoleId())).complete();
                    log.info("Announced YouTube {} {} for {} in {}", video.isShort() ? "Short" : "video",
                            video.videoId(), s.name(), target.channel().getGuild().getName());
                } catch (Exception e) {
                    log.warn("Failed to announce {} in guild {}: {}", video.videoId(), s.guildId(), e.getMessage());
                }
            }
        }
    }
}
