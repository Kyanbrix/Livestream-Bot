package com.github.kyanbrix.platform;

import java.time.Instant;

/**
 * Snapshot of a live stream as reported by a platform.
 *
 * @param watchUrl link to the stream itself, or null to link the channel page
 */
public record StreamInfo(
        Platform platform,
        String username,
        String displayName,
        String title,
        String game,
        int viewers,
        String thumbnailUrl,
        String avatarUrl,
        String gameImageUrl,
        Instant startedAt,
        String watchUrl
) {
    /**
     * @return the stream's own page if the platform has one (YouTube), otherwise the channel page
     */
    public String url() {
        return watchUrl != null ? watchUrl : platform.channelUrl(username);
    }
}
