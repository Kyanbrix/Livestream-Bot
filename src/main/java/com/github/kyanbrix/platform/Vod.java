package com.github.kyanbrix.platform;

/**
 * A recording of a finished stream.
 *
 * @param thumbnailUrl preview image, or null if the platform hasn't generated one yet
 */
public record Vod(String url, String thumbnailUrl) {
}
