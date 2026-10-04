package com.github.kyanbrix.platform;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

public interface StreamProvider {

    Platform platform();

    /**
     * Fetches the streams that are currently live.
     *
     * @param usernames lowercase usernames/slugs to check
     * @return live streams keyed by lowercase username; offline users are absent
     * @throws IOException if the platform API could not be reached or returned an error
     */
    Map<String, StreamInfo> fetchLive(Collection<String> usernames) throws IOException, InterruptedException;

    /**
     * @return true if a channel with this username/slug exists on the platform
     */
    boolean exists(String username) throws IOException, InterruptedException;
}
