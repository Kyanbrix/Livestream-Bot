package com.github.kyanbrix.platform;

/**
 * A channel resolved from user input.
 *
 * @param id          the stable identifier stored in the database (Twitch/Kick login, YouTube channel ID)
 * @param displayName the name shown to users
 */
public record Channel(String id, String displayName) {
}
