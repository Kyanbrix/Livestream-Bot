package com.github.kyanbrix.platform;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface StreamProvider {

    /** How far a recording's start time may differ from the stream's to count as its VOD. */
    Duration VOD_MATCH_WINDOW = Duration.ofMinutes(10);

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
     * Turns user input (a name, handle or channel URL) into the channel to track.
     *
     * @return empty if the input isn't valid or no such channel exists
     */
    Optional<Channel> resolve(String input) throws IOException, InterruptedException;

    /**
     * Looks up the recording of a stream that started at {@code startedAt}.
     *
     * @return empty if the streamer doesn't keep VODs or it isn't available yet
     */
    Optional<Vod> findVod(String username, Instant startedAt) throws IOException, InterruptedException;

    static boolean sameBroadcast(Instant a, Instant b) {
        return a != null && b != null && Duration.between(a, b).abs().compareTo(VOD_MATCH_WINDOW) <= 0;
    }
}
