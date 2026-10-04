package com.github.kyanbrix.notify;

import com.github.kyanbrix.db.Database;
import com.github.kyanbrix.db.Database.GuildSettings;
import com.github.kyanbrix.db.Database.LiveState;
import com.github.kyanbrix.db.Database.TrackedStreamer;
import com.github.kyanbrix.platform.Platform;
import com.github.kyanbrix.platform.StreamInfo;
import com.github.kyanbrix.platform.StreamProvider;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically asks each platform which tracked streamers are live and posts, refreshes
 * or closes the corresponding announcement in every guild that tracks them.
 */
public class StreamPoller implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StreamPoller.class);

    /** A stream whose start time moved by more than this is treated as a new broadcast. */
    private static final Duration RESTART_THRESHOLD = Duration.ofMinutes(2);

    private final JDA jda;
    private final Database db;
    private final Map<Platform, StreamProvider> providers;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stream-poller");
        t.setDaemon(true);
        return t;
    });

    public StreamPoller(JDA jda, Database db, Map<Platform, StreamProvider> providers) {
        this.jda = jda;
        this.db = db;
        this.providers = providers;
    }

    public void start(long intervalSeconds) {
        scheduler.scheduleWithFixedDelay(this::safeTick, 5, intervalSeconds, TimeUnit.SECONDS);
        log.info("Polling {} every {}s", providers.keySet(), intervalSeconds);
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            // An escaped exception would silently cancel the scheduled task.
            log.error("Poll tick failed", t);
        }
    }

    private void tick() throws Exception {
        List<TrackedStreamer> tracked = db.getAllStreamers();
        if (tracked.isEmpty()) {
            return;
        }

        Map<Platform, Set<String>> usernames = new EnumMap<>(Platform.class);
        for (TrackedStreamer s : tracked) {
            usernames.computeIfAbsent(s.platform(), p -> new HashSet<>()).add(s.username());
        }

        // Only platforms present here were fetched successfully; others are skipped this tick so an
        // API outage doesn't mark everyone as offline.
        Map<Platform, Map<String, StreamInfo>> live = new EnumMap<>(Platform.class);
        for (var entry : usernames.entrySet()) {
            StreamProvider provider = providers.get(entry.getKey());
            if (provider == null) {
                continue;
            }
            try {
                live.put(entry.getKey(), provider.fetchLive(entry.getValue()));
            } catch (Exception e) {
                log.warn("Failed to fetch {} streams: {}", entry.getKey(), e.getMessage());
            }
        }

        Map<Long, GuildSettings> settingsCache = new HashMap<>();
        for (TrackedStreamer s : tracked) {
            Map<String, StreamInfo> platformLive = live.get(s.platform());
            if (platformLive == null) {
                continue;
            }
            try {
                GuildSettings settings = settingsCache.computeIfAbsent(s.guildId(), this::settings);
                process(s, settings, platformLive.get(s.username()));
            } catch (Exception e) {
                log.warn("Failed to update {} {} in guild {}: {}", s.platform(), s.username(), s.guildId(), e.getMessage());
            }
        }
    }

    private void process(TrackedStreamer s, GuildSettings settings, StreamInfo stream) throws Exception {
        Optional<LiveState> state = db.getLiveState(s.guildId(), s.platform(), s.username());

        if (stream == null) {
            if (state.isPresent()) {
                end(state.get());
            }
            return;
        }

        if (state.isEmpty()) {
            announce(s.guildId(), settings, stream);
        } else if (isNewBroadcast(state.get(), stream)) {
            end(state.get());
            announce(s.guildId(), settings, stream);
        } else {
            refresh(state.get(), stream);
        }
    }

    private static boolean isNewBroadcast(LiveState state, StreamInfo stream) {
        return state.startedAt() != null && stream.startedAt() != null
                && stream.startedAt().isAfter(state.startedAt().plus(RESTART_THRESHOLD));
    }

    private void announce(long guildId, GuildSettings settings, StreamInfo stream) throws Exception {
        if (settings.channelId() == null) {
            return;
        }
        GuildMessageChannel channel = channel(guildId, settings.channelId());
        if (channel == null) {
            log.debug("Notification channel {} for guild {} is unavailable", settings.channelId(), guildId);
            return;
        }
        Guild guild = channel.getGuild();
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            log.warn("Missing permissions to post in #{} ({})", channel.getName(), guild.getName());
            return;
        }

        Long pingRole = settings.pingRoleId() != null && guild.getRoleById(settings.pingRoleId()) != null
                ? settings.pingRoleId()
                : null;
        List<String> games = stream.game().isBlank() ? List.of() : List.of(stream.game());
        Message message = channel.sendMessage(EmbedFactory.liveMessage(stream, pingRole, games)).complete();
        db.saveLiveState(new LiveState(guildId, stream.platform(), stream.username(),
                channel.getIdLong(), message.getIdLong(), stream.startedAt(), games));
        log.info("Announced {} {} in {}", stream.platform(), stream.username(), guild.getName());
    }

    private void refresh(LiveState state, StreamInfo stream) throws Exception {
        List<String> games = state.games();
        if (!stream.game().isBlank() && (games.isEmpty() || !games.getLast().equals(stream.game()))) {
            games = new ArrayList<>(games);
            games.add(stream.game());
            db.saveLiveState(new LiveState(state.guildId(), state.platform(), state.username(),
                    state.channelId(), state.messageId(), state.startedAt(), games));
            log.info("{} {} switched to {}", stream.platform(), stream.username(), stream.game());
        }

        GuildMessageChannel channel = channel(state.guildId(), state.channelId());
        if (channel == null) {
            return;
        }
        try {
            channel.editMessageById(state.messageId(), EmbedFactory.liveUpdate(stream, games)).complete();
        } catch (ErrorResponseException e) {
            // The announcement was deleted by a moderator; keep the state so we don't repost it.
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                throw e;
            }
        }
    }

    private void end(LiveState state) throws Exception {
        db.deleteLiveState(state.guildId(), state.platform(), state.username());
        GuildMessageChannel channel = channel(state.guildId(), state.channelId());
        if (channel == null) {
            return;
        }
        try {
            Message original = channel.retrieveMessageById(state.messageId()).complete();
            original.editMessage(EmbedFactory.endedUpdate(original, state.startedAt(), state.games())).complete();
            log.info("Marked {} {} as ended in guild {}", state.platform(), state.username(), state.guildId());
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                throw e;
            }
        }
    }

    private GuildMessageChannel channel(long guildId, long channelId) {
        Guild guild = jda.getGuildById(guildId);
        return guild == null ? null : guild.getChannelById(GuildMessageChannel.class, channelId);
    }

    private GuildSettings settings(long guildId) {
        try {
            return db.getSettings(guildId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
