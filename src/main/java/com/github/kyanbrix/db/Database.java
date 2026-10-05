package com.github.kyanbrix.db;

import com.github.kyanbrix.platform.Channel;
import com.github.kyanbrix.platform.Platform;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class Database implements AutoCloseable {

    public record GuildSettings(long guildId, Long channelId, Long pingRoleId) {
    }

    /**
     * @param username     stable channel identifier (Twitch/Kick login, YouTube channel ID)
     * @param notifyVideos YouTube only: announce new videos
     * @param notifyShorts YouTube only: announce new Shorts
     */
    public record TrackedStreamer(long guildId, Platform platform, String username, String displayName,
                                  boolean notifyVideos, boolean notifyShorts) {
        public String name() {
            return displayName != null ? displayName : username;
        }
    }

    /**
     * @param games every game/category played during the stream, oldest first; the last entry is current
     */
    public record LiveState(long guildId, Platform platform, String username, long channelId, long messageId,
                            Instant startedAt, List<String> games) {
    }

    private static final String SELECT_STREAMERS =
            "SELECT guild_id, platform, username, display_name, notify_videos, notify_shorts FROM streamers ";

    private final Connection connection;

    public Database(String path) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_settings (
                        guild_id     INTEGER PRIMARY KEY,
                        channel_id   INTEGER,
                        ping_role_id INTEGER
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS streamers (
                        guild_id      INTEGER NOT NULL,
                        platform      TEXT    NOT NULL,
                        username      TEXT    NOT NULL,
                        display_name  TEXT,
                        notify_videos INTEGER NOT NULL DEFAULT 1,
                        notify_shorts INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY (guild_id, platform, username)
                    )""");
            // YouTube videos already seen per channel, shared by all guilds, so only new uploads are announced.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS youtube_seen (
                        channel_id TEXT NOT NULL,
                        video_id   TEXT NOT NULL,
                        PRIMARY KEY (channel_id, video_id)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS live_state (
                        guild_id   INTEGER NOT NULL,
                        platform   TEXT    NOT NULL,
                        username   TEXT    NOT NULL,
                        channel_id INTEGER NOT NULL,
                        message_id INTEGER NOT NULL,
                        started_at INTEGER,
                        games      TEXT,
                        PRIMARY KEY (guild_id, platform, username)
                    )""");
            migrate(st);
        }
    }

    /**
     * Upgrades databases created by older versions of the bot.
     */
    private static void migrate(Statement st) throws SQLException {
        addColumnIfMissing(st, "live_state", "games", "TEXT");
        addColumnIfMissing(st, "streamers", "display_name", "TEXT");
        addColumnIfMissing(st, "streamers", "notify_videos", "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing(st, "streamers", "notify_shorts", "INTEGER NOT NULL DEFAULT 1");
    }

    private static void addColumnIfMissing(Statement st, String table, String column, String definition) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return;
                }
            }
        }
        st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    // ---- guild settings ----

    public synchronized GuildSettings getSettings(long guildId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT channel_id, ping_role_id FROM guild_settings WHERE guild_id = ?")) {
            ps.setLong(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new GuildSettings(guildId, null, null);
                }
                return new GuildSettings(guildId, nullableLong(rs, 1), nullableLong(rs, 2));
            }
        }
    }

    public synchronized void setChannel(long guildId, long channelId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO guild_settings (guild_id, channel_id) VALUES (?, ?)
                ON CONFLICT(guild_id) DO UPDATE SET channel_id = excluded.channel_id""")) {
            ps.setLong(1, guildId);
            ps.setLong(2, channelId);
            ps.executeUpdate();
        }
    }

    public synchronized void setPingRole(long guildId, Long roleId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO guild_settings (guild_id, ping_role_id) VALUES (?, ?)
                ON CONFLICT(guild_id) DO UPDATE SET ping_role_id = excluded.ping_role_id""")) {
            ps.setLong(1, guildId);
            ps.setObject(2, roleId);
            ps.executeUpdate();
        }
    }

    // ---- streamers ----

    /**
     * @return false if the streamer was already tracked in this guild
     */
    public synchronized boolean addStreamer(long guildId, Platform platform, Channel channel,
                                            boolean notifyVideos, boolean notifyShorts) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR IGNORE INTO streamers (guild_id, platform, username, display_name, notify_videos, notify_shorts)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            ps.setLong(1, guildId);
            ps.setString(2, platform.name());
            ps.setString(3, channel.id());
            ps.setString(4, channel.displayName());
            ps.setBoolean(5, notifyVideos);
            ps.setBoolean(6, notifyShorts);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Removes a streamer by username/channel ID or display name, ignoring case.
     *
     * @return the removed streamer, or empty if nothing matched
     */
    public synchronized Optional<TrackedStreamer> removeStreamer(long guildId, Platform platform, String nameOrId) throws SQLException {
        Optional<TrackedStreamer> match;
        try (PreparedStatement ps = connection.prepareStatement(SELECT_STREAMERS + """
                WHERE guild_id = ? AND platform = ?
                  AND (username = ? COLLATE NOCASE OR display_name = ? COLLATE NOCASE)""")) {
            ps.setLong(1, guildId);
            ps.setString(2, platform.name());
            ps.setString(3, nameOrId);
            ps.setString(4, nameOrId);
            match = readStreamers(ps).stream().findFirst();
        }
        if (match.isEmpty()) {
            return match;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM streamers WHERE guild_id = ? AND platform = ? AND username = ?")) {
            ps.setLong(1, guildId);
            ps.setString(2, platform.name());
            ps.setString(3, match.get().username());
            ps.executeUpdate();
        }
        deleteLiveState(guildId, platform, match.get().username());
        return match;
    }

    public synchronized List<TrackedStreamer> getStreamers(long guildId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                SELECT_STREAMERS + " WHERE guild_id = ? ORDER BY platform, COALESCE(display_name, username) COLLATE NOCASE")) {
            ps.setLong(1, guildId);
            return readStreamers(ps);
        }
    }

    public synchronized List<TrackedStreamer> getAllStreamers() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_STREAMERS)) {
            return readStreamers(ps);
        }
    }

    private List<TrackedStreamer> readStreamers(PreparedStatement ps) throws SQLException {
        List<TrackedStreamer> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new TrackedStreamer(rs.getLong(1), Platform.valueOf(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getBoolean(5), rs.getBoolean(6)));
            }
        }
        return list;
    }

    // ---- live state ----

    public synchronized Optional<LiveState> getLiveState(long guildId, Platform platform, String username) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT channel_id, message_id, started_at, games FROM live_state
                WHERE guild_id = ? AND platform = ? AND username = ?""")) {
            ps.setLong(1, guildId);
            ps.setString(2, platform.name());
            ps.setString(3, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Long started = nullableLong(rs, 3);
                String games = rs.getString(4);
                return Optional.of(new LiveState(guildId, platform, username, rs.getLong(1), rs.getLong(2),
                        started == null ? null : Instant.ofEpochSecond(started),
                        games == null || games.isEmpty() ? List.of() : List.of(games.split("\n"))));
            }
        }
    }

    public synchronized void saveLiveState(LiveState state) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO live_state (guild_id, platform, username, channel_id, message_id, started_at, games)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setLong(1, state.guildId());
            ps.setString(2, state.platform().name());
            ps.setString(3, state.username());
            ps.setLong(4, state.channelId());
            ps.setLong(5, state.messageId());
            ps.setObject(6, state.startedAt() == null ? null : state.startedAt().getEpochSecond());
            ps.setString(7, String.join("\n", state.games()));
            ps.executeUpdate();
        }
    }

    public synchronized void deleteLiveState(long guildId, Platform platform, String username) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM live_state WHERE guild_id = ? AND platform = ? AND username = ?")) {
            ps.setLong(1, guildId);
            ps.setString(2, platform.name());
            ps.setString(3, username);
            ps.executeUpdate();
        }
    }

    // ---- YouTube seen videos ----

    /** Marker stored when seeding a channel with an empty feed, so it still counts as seeded. */
    private static final String SEEDED = "";

    /**
     * @return true once the channel's feed has been recorded at least once
     */
    public synchronized boolean hasSeen(String channelId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM youtube_seen WHERE channel_id = ? LIMIT 1")) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * @return the IDs from {@code videoIds} that haven't been seen for this channel
     */
    public synchronized Set<String> unseen(String channelId, Collection<String> videoIds) throws SQLException {
        Set<String> unseen = new LinkedHashSet<>(videoIds);
        try (PreparedStatement ps = connection.prepareStatement("SELECT video_id FROM youtube_seen WHERE channel_id = ?")) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    unseen.remove(rs.getString(1));
                }
            }
        }
        return unseen;
    }

    public synchronized void markSeen(String channelId, Collection<String> videoIds) throws SQLException {
        List<String> ids = new ArrayList<>(videoIds);
        ids.add(SEEDED);
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO youtube_seen (channel_id, video_id) VALUES (?, ?)")) {
            for (String id : ids) {
                ps.setString(1, channelId);
                ps.setString(2, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Forgets videos that are no longer in the channel's feed; they can't reappear as "new" anyway.
     */
    public synchronized void pruneSeen(String channelId, Collection<String> keepIds) throws SQLException {
        Set<String> keep = new HashSet<>(keepIds);
        keep.add(SEEDED);
        List<String> stale = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT video_id FROM youtube_seen WHERE channel_id = ?")) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!keep.contains(rs.getString(1))) {
                        stale.add(rs.getString(1));
                    }
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM youtube_seen WHERE channel_id = ? AND video_id = ?")) {
            for (String id : stale) {
                ps.setString(1, channelId);
                ps.setString(2, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
