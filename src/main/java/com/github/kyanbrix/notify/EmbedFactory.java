package com.github.kyanbrix.notify;

import com.github.kyanbrix.platform.Platform;
import com.github.kyanbrix.platform.StreamInfo;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;

public final class EmbedFactory {

    private static final Color ENDED_COLOR = new Color(0x4F545C);

    private EmbedFactory() {
    }

    /**
     * The go-live announcement, optionally pinging a role.
     */
    public static MessageCreateData liveMessage(StreamInfo stream, Long pingRoleId) {
        MessageCreateBuilder builder = new MessageCreateBuilder()
                .setEmbeds(liveEmbed(stream))
                .setComponents(ActionRow.of(watchButton(stream)))
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
        if (pingRoleId != null) {
            builder.setContent("<@&" + pingRoleId + "> **" + stream.displayName() + "** is live on "
                    + stream.platform().getDisplayName() + "!");
            builder.mentionRoles(pingRoleId);
        } else {
            builder.setContent("**" + stream.displayName() + "** is live on " + stream.platform().getDisplayName() + "!");
        }
        return builder.build();
    }

    /**
     * Refreshes viewer count, title and category on an existing announcement. Edits never re-ping.
     */
    public static MessageEditData liveUpdate(StreamInfo stream) {
        return new MessageEditBuilder()
                .setEmbeds(liveEmbed(stream))
                .setComponents(ActionRow.of(watchButton(stream)))
                .build();
    }

    /**
     * Turns the announcement into an "ended" notice, keeping the last known title and category.
     */
    public static MessageEditData endedUpdate(Message original, Instant startedAt) {
        EmbedBuilder embed = original.getEmbeds().isEmpty()
                ? new EmbedBuilder()
                : new EmbedBuilder(original.getEmbeds().getFirst());

        MessageEmbed.AuthorInfo author = original.getEmbeds().isEmpty() ? null : original.getEmbeds().getFirst().getAuthor();
        String name = author != null && author.getName() != null
                ? author.getName().replace(" is now live!", "")
                : "The stream";

        embed.setColor(ENDED_COLOR)
                .setAuthor(name + " was live", author != null ? author.getUrl() : null, author != null ? author.getIconUrl() : null)
                .setImage(null)
                .clearFields()
                .setDescription(startedAt != null
                        ? "Stream ended after **" + formatDuration(Duration.between(startedAt, Instant.now())) + "**."
                        : "Stream ended.")
                .setTimestamp(Instant.now());

        String content = original.getContentRaw().replaceFirst("^<@&\\d+> ", "").replace(" is live on ", " was live on ");
        return new MessageEditBuilder()
                .setContent(content)
                .setEmbeds(embed.build())
                .build();
    }

    private static MessageEmbed liveEmbed(StreamInfo stream) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(stream.platform().getColor())
                .setAuthor(stream.displayName() + " is now live!", stream.url(), stream.avatarUrl())
                .setTitle(truncate(stream.title().isBlank() ? stream.url() : stream.title(), MessageEmbed.TITLE_MAX_LENGTH), stream.url())
                .addField(stream.platform() == Platform.KICK ? "Category" : "Game",
                        stream.game().isBlank() ? "—" : stream.game(), true)
                .addField("Viewers", String.format("%,d", stream.viewers()), true)
                .setImage(stream.thumbnailUrl())
                .setThumbnail(stream.avatarUrl())
                .setFooter(stream.platform().getDisplayName())
                .setTimestamp(stream.startedAt());
        return embed.build();
    }

    private static Button watchButton(StreamInfo stream) {
        return Button.link(stream.url(), "Watch on " + stream.platform().getDisplayName());
    }

    private static String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return hours > 0 ? hours + "h " + minutes + "m" : Math.max(1, minutes) + "m";
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
