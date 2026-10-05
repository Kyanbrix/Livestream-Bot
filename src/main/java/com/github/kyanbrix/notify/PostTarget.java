package com.github.kyanbrix.notify;

import com.github.kyanbrix.db.Database.GuildSettings;
import com.github.kyanbrix.db.Database.TrackedStreamer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where a guild's notifications go, and which role (if any) they ping.
 */
public record PostTarget(GuildMessageChannel channel, Long pingRoleId) {

    private static final Logger log = LoggerFactory.getLogger(PostTarget.class);

    /**
     * Resolves where a streamer's posts go: its own channel and role if set, otherwise the server defaults.
     *
     * @return null if there is no usable notification channel
     */
    public static PostTarget of(JDA jda, GuildSettings settings, TrackedStreamer streamer) {
        Long channelId = effectiveChannelId(settings, streamer);
        if (channelId == null) {
            return null;
        }
        Guild guild = jda.getGuildById(settings.guildId());
        GuildMessageChannel channel = guild == null ? null : guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) {
            log.debug("Notification channel {} for guild {} is unavailable", channelId, settings.guildId());
            return null;
        }
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            log.warn("Missing permissions to post in #{} ({})", channel.getName(), guild.getName());
            return null;
        }
        // A deleted role would render as "@deleted-role": fall back to the server role, then to no ping.
        Long pingRole = null;
        for (Long roleId : new Long[]{streamer.pingRoleId(), settings.pingRoleId()}) {
            if (roleId != null && guild.getRoleById(roleId) != null) {
                pingRole = roleId;
                break;
            }
        }
        return new PostTarget(channel, pingRole);
    }

    public static Long effectiveChannelId(GuildSettings settings, TrackedStreamer streamer) {
        return streamer.channelId() != null ? streamer.channelId() : settings.channelId();
    }

    public static Long effectiveRoleId(GuildSettings settings, TrackedStreamer streamer) {
        return streamer.pingRoleId() != null ? streamer.pingRoleId() : settings.pingRoleId();
    }
}
