package com.github.kyanbrix.platform;

import java.awt.Color;

public enum Platform {
    TWITCH("Twitch", new Color(0x9146FF), "https://twitch.tv/"),
    KICK("Kick", new Color(0x53FC18), "https://kick.com/"),
    YOUTUBE("YouTube", new Color(0xFF0000), "https://www.youtube.com/channel/");

    private final String displayName;
    private final Color color;
    private final String baseUrl;

    Platform(String displayName, Color color, String baseUrl) {
        this.displayName = displayName;
        this.color = color;
        this.baseUrl = baseUrl;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Color getColor() {
        return color;
    }

    public String channelUrl(String username) {
        return baseUrl + username;
    }

    public static Platform fromString(String value) {
        return Platform.valueOf(value.trim().toUpperCase());
    }
}
