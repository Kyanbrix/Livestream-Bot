package com.github.kyanbrix.platform;

import java.time.Instant;

/**
 * A video from a channel's upload feed.
 *
 * @param isShort true if YouTube links it as a Short ({@code /shorts/ID})
 */
public record YouTubeVideo(String videoId, String channelId, String channelTitle, String title,
                           Instant published, String thumbnailUrl, boolean isShort) {

    public String url() {
        return isShort
                ? "https://www.youtube.com/shorts/" + videoId
                : "https://www.youtube.com/watch?v=" + videoId;
    }

    public YouTubeVideo withThumbnail(String thumbnailUrl) {
        return new YouTubeVideo(videoId, channelId, channelTitle, title, published, thumbnailUrl, isShort);
    }
}
