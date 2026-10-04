package com.github.kyanbrix.platform;

import java.time.Instant;

/**
 * Snapshot of a live stream as reported by a platform.
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
        Instant startedAt
) {
    public String url() {
        return platform.channelUrl(username);
    }
}
