package com.github.kyanbrix.notify;

import com.github.kyanbrix.platform.Platform;
import com.github.kyanbrix.platform.StreamInfo;
import com.github.kyanbrix.platform.Vod;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.TimeFormat;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

public final class EmbedFactory {

    private static final Color ENDED_COLOR = new Color(0x4F545C);

    private EmbedFactory() {
    }

    /**
     * The go-live announcement, optionally pinging a role.
     */
    public static MessageCreateData liveMessage(StreamInfo stream, Long pingRoleId, List<String> games) {
        MessageCreateBuilder builder = new MessageCreateBuilder()
                .setEmbeds(liveEmbed(stream, games))
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
     * Refreshes viewer count, title and game on an existing announcement. Edits never re-ping.
     */
    public static MessageEditData liveUpdate(StreamInfo stream, List<String> games) {
        return new MessageEditBuilder()
                .setEmbeds(liveEmbed(stream, games))
                .setComponents(ActionRow.of(watchButton(stream)))
                .build();
    }

    /**
     * Turns the announcement into an "ended" notice, keeping the last known title, category and image.
     *
     * @param vod the stream's recording, or null to keep the channel button
     */
    public static MessageEditData endedUpdate(Message original, Instant startedAt, List<String> games, Vod vod) {
        EmbedBuilder embed = original.getEmbeds().isEmpty()
                ? new EmbedBuilder()
                : new EmbedBuilder(original.getEmbeds().getFirst());

        MessageEmbed.AuthorInfo author = original.getEmbeds().isEmpty() ? null : original.getEmbeds().getFirst().getAuthor();
        String name = author != null && author.getName() != null
                ? author.getName().replace(" is now live!", "")
                : "The stream";

        Instant endedAt = Instant.now();
        String description = "Stream ended " + TimeFormat.RELATIVE.format(endedAt);
        if (startedAt != null) {
            description += " · lasted **" + formatDuration(Duration.between(startedAt, endedAt)) + "**";
        }
        embed.setColor(ENDED_COLOR)
                .setAuthor(name + " was live", author != null ? author.getUrl() : null, author != null ? author.getIconUrl() : null)
                .clearFields()
                .setDescription(description)
                .setTimestamp(endedAt);
        if (vod != null && vod.thumbnailUrl() != null) {
            // The live preview goes stale once a stream ends; the VOD's own thumbnail doesn't.
            embed.setImage(vod.thumbnailUrl());
        }
        if (!games.isEmpty()) {
            embed.addField("Played", gameHistory(games), false);
        }

        String content = original.getContentRaw().replaceFirst("^<@&\\d+> ", "").replace(" is live on ", " was live on ");
        MessageEditBuilder builder = new MessageEditBuilder()
                .setContent(content)
                .setEmbeds(embed.build());
        if (vod != null) {
            builder.setComponents(ActionRow.of(vodButton(vod)));
        }
        return builder.build();
    }

    /**
     * Adds a recording that became available after the stream was already marked as ended.
     */
    public static MessageEditData vodUpdate(Message original, Vod vod) {
        MessageEditBuilder builder = new MessageEditBuilder().setComponents(ActionRow.of(vodButton(vod)));
        if (vod.thumbnailUrl() != null && !original.getEmbeds().isEmpty()) {
            builder.setEmbeds(new EmbedBuilder(original.getEmbeds().getFirst()).setImage(vod.thumbnailUrl()).build());
        }
        return builder.build();
    }

    /**
     * @param games game history for this stream, oldest first; everything before the current game is listed as "Previously"
     */
    private static MessageEmbed liveEmbed(StreamInfo stream, List<String> games) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(stream.platform().getColor())
                .setAuthor(stream.displayName() + " is now live!", stream.url(), stream.avatarUrl())
                .setTitle(truncate(stream.title().isBlank() ? stream.url() : stream.title(), MessageEmbed.TITLE_MAX_LENGTH), stream.url())
                // Rendered by Discord as e.g. "2 hours ago" and kept current client-side.
                .setDescription(stream.startedAt() != null ? "🔴 Live since " + TimeFormat.RELATIVE.format(stream.startedAt()) : null)
                .addField(stream.platform() == Platform.KICK ? "Category" : "Game",
                        stream.game().isBlank() ? "—" : stream.game(), true)
                .addField("Viewers", String.format("%,d", stream.viewers()), true)
                .setImage(stream.thumbnailUrl())
                .setThumbnail(stream.gameImageUrl())
                .setFooter(stream.platform().getDisplayName())
                .setTimestamp(stream.startedAt());
        if (games.size() > 1) {
            embed.addField("Previously", gameHistory(games.subList(0, games.size() - 1)), false);
        }
        return embed.build();
    }

    private static Button watchButton(StreamInfo stream) {
        return Button.link(stream.url(), "Watch on " + stream.platform().getDisplayName());
    }

    /**
     * Joins games as "A → B → C", dropping the oldest ones if it would exceed the embed field limit.
     */
    private static String gameHistory(List<String> games) {
        String joined = String.join(" → ", games);
        for (int start = 1; joined.length() > MessageEmbed.VALUE_MAX_LENGTH && start < games.size(); start++) {
            joined = "… → " + String.join(" → ", games.subList(start, games.size()));
        }
        return truncate(joined, MessageEmbed.VALUE_MAX_LENGTH);
    }

    private static Button vodButton(Vod vod) {
        return Button.link(vod.url(), "Watch VOD");
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
