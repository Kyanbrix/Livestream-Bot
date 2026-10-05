package com.github.kyanbrix.notify;

import com.github.kyanbrix.db.Database.GuildSettings;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where a guild's notifications go, and which role (if any) they ping.
 */
record PostTarget(GuildMessageChannel channel, Long pingRoleId) {

    private static final Logger log = LoggerFactory.getLogger(PostTarget.class);

    /**
     * @return null if the guild has no usable notification channel
     */
    static PostTarget of(JDA jda, GuildSettings settings) {
        if (settings.channelId() == null) {
            return null;
        }
        Guild guild = jda.getGuildById(settings.guildId());
        GuildMessageChannel channel = guild == null ? null : guild.getChannelById(GuildMessageChannel.class, settings.channelId());
        if (channel == null) {
            log.debug("Notification channel {} for guild {} is unavailable", settings.channelId(), settings.guildId());
            return null;
        }
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            log.warn("Missing permissions to post in #{} ({})", channel.getName(), guild.getName());
            return null;
        }
        // A deleted role would render as "@deleted-role", so drop it.
        Long pingRole = settings.pingRoleId() != null && guild.getRoleById(settings.pingRoleId()) != null
                ? settings.pingRoleId()
                : null;
        return new PostTarget(channel, pingRole);
    }
}
