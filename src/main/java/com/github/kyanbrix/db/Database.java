package com.github.kyanbrix.db;

import com.github.kyanbrix.platform.Platform;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Database implements AutoCloseable {

    public record GuildSettings(long guildId, Long channelId, Long pingRoleId) {
    }

    public record TrackedStreamer(long guildId, Platform platform, String username) {
    }

    public record LiveState(long guildId, Platform platform, String username, long channelId, long messageId, Instant startedAt) {
    }

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
                        guild_id INTEGER NOT NULL,
                        platform TEXT    NOT NULL,
                        username TEXT    NOT NULL,
                        PRIMARY KEY (guild_id, platform, username)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS live_state (
                        guild_id   INTEGER NOT NULL,
                        platform   TEXT    NOT NULL,
                        username   TEXT    NOT NULL,
                        channel_id INTEGER NOT NULL,
                        message_id INTEGER NOT NULL,
                        started_at INTEGER,
                        PRIMARY KEY (guild_id, platform, username)
                    )""");
        }
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
    public synchronized boolean addStreamer(long guildId, Platform platform, String username) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO streamers (guild_id, platform, username) VALUES (?, ?, ?)")) {
            ps.setLong(1, guildId);
            ps.setString(2, platform.name());
            ps.setString(3, username);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * @return false if the streamer was not tracked in this guild
     */
    public synchronized boolean removeStreamer(long guildId, Platform platform, String username) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM streamers WHERE guild_id = ? AND platform = ? AND username = ?")) {
            ps.setLong(1, guildId);
            ps.setString(2, platform.name());
            ps.setString(3, username);
            boolean removed = ps.executeUpdate() > 0;
            deleteLiveState(guildId, platform, username);
            return removed;
        }
    }

    public synchronized List<TrackedStreamer> getStreamers(long guildId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT guild_id, platform, username FROM streamers WHERE guild_id = ? ORDER BY platform, username")) {
            ps.setLong(1, guildId);
            return readStreamers(ps);
        }
    }

    public synchronized List<TrackedStreamer> getAllStreamers() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT guild_id, platform, username FROM streamers")) {
            return readStreamers(ps);
        }
    }

    private List<TrackedStreamer> readStreamers(PreparedStatement ps) throws SQLException {
        List<TrackedStreamer> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new TrackedStreamer(rs.getLong(1), Platform.valueOf(rs.getString(2)), rs.getString(3)));
            }
        }
        return list;
    }

    // ---- live state ----

    public synchronized Optional<LiveState> getLiveState(long guildId, Platform platform, String username) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT channel_id, message_id, started_at FROM live_state
                WHERE guild_id = ? AND platform = ? AND username = ?""")) {
            ps.setLong(1, guildId);
            ps.setString(2, platform.name());
            ps.setString(3, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Long started = nullableLong(rs, 3);
                return Optional.of(new LiveState(guildId, platform, username, rs.getLong(1), rs.getLong(2),
                        started == null ? null : Instant.ofEpochSecond(started)));
            }
        }
    }

    public synchronized void saveLiveState(LiveState state) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO live_state (guild_id, platform, username, channel_id, message_id, started_at)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            ps.setLong(1, state.guildId());
            ps.setString(2, state.platform().name());
            ps.setString(3, state.username());
            ps.setLong(4, state.channelId());
            ps.setLong(5, state.messageId());
            ps.setObject(6, state.startedAt() == null ? null : state.startedAt().getEpochSecond());
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

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
