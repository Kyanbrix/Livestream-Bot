package com.github.kyanbrix.command;

import com.github.kyanbrix.db.Database;
import com.github.kyanbrix.db.Database.GuildSettings;
import com.github.kyanbrix.db.Database.TrackedStreamer;
import com.github.kyanbrix.notify.EmbedFactory;
import com.github.kyanbrix.platform.Channel;
import com.github.kyanbrix.platform.Platform;
import com.github.kyanbrix.platform.StreamInfo;
import com.github.kyanbrix.platform.StreamProvider;
import com.github.kyanbrix.platform.TwitchProvider;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.Optional;
import java.util.concurrent.Executors;

public class CommandListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

    private final Database db;
    private final Map<Platform, StreamProvider> providers;
    // Platform API calls and DB writes run here so they never block JDA's event thread.
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    public CommandListener(Database db, Map<Platform, StreamProvider> providers) {
        this.db = db;
        this.providers = providers;
    }

    public static SlashCommandData commandData() {
        OptionData platform = new OptionData(OptionType.STRING, "platform", "Streaming platform", true)
                .addChoice("Twitch", "twitch")
                .addChoice("Kick", "kick")
                .addChoice("YouTube", "youtube");

        return Commands.slash("stream", "Manage live stream notifications")
                .setContexts(InteractionContextType.GUILD)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addSubcommands(
                        new SubcommandData("add", "Notify when a streamer goes live (YouTube: also new videos and Shorts)")
                                .addOptions(platform,
                                        new OptionData(OptionType.STRING, "username", "Channel name, @handle or channel URL", true),
                                        new OptionData(OptionType.BOOLEAN, "videos", "YouTube: announce new videos (default: yes)", false),
                                        new OptionData(OptionType.BOOLEAN, "shorts", "YouTube: announce new Shorts (default: yes)", false)),
                        new SubcommandData("remove", "Stop tracking a streamer")
                                .addOptions(platform,
                                        new OptionData(OptionType.STRING, "username", "Tracked channel name", true, true)),
                        new SubcommandData("list", "Show tracked streamers and settings"),
                        new SubcommandData("channel", "Set the channel where notifications are posted")
                                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Notification channel", true)
                                        .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS)),
                        new SubcommandData("role", "Set a role to ping (leave empty to disable pings)")
                                .addOption(OptionType.ROLE, "role", "Role to mention", false),
                        new SubcommandData("test", "Post a sample notification to check your setup")
                );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("stream") || event.getGuild() == null || event.getSubcommandName() == null) {
            return;
        }
        event.deferReply(true).queue();
        InteractionHook hook = event.getHook();
        Guild guild = event.getGuild();
        String sub = event.getSubcommandName();

        worker.submit(() -> {
            try {
                String reply = switch (sub) {
                    case "add" -> add(guild, platformOption(event), usernameOption(event),
                            event.getOption("videos", true, OptionMapping::getAsBoolean),
                            event.getOption("shorts", true, OptionMapping::getAsBoolean));
                    case "remove" -> remove(guild, platformOption(event), usernameOption(event));
                    case "list" -> list(guild);
                    case "channel" -> channel(guild, event.getOption("channel", OptionMapping::getAsChannel).asGuildMessageChannel());
                    case "role" -> role(guild, event.getOption("role", OptionMapping::getAsRole));
                    case "test" -> test(guild);
                    default -> "Unknown subcommand.";
                };
                hook.sendMessage(reply).queue();
            } catch (Exception e) {
                log.error("/stream {} failed in {}", sub, guild.getName(), e);
                hook.sendMessage("Something went wrong: " + e.getMessage()).queue();
            }
        });
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("stream") || !"remove".equals(event.getSubcommandName()) || event.getGuild() == null) {
            return;
        }
        String typed = event.getFocusedOption().getValue().toLowerCase();
        String platform = event.getOption("platform", OptionMapping::getAsString);
        try {
            List<Command.Choice> choices = db.getStreamers(event.getGuild().getIdLong()).stream()
                    .filter(s -> platform == null || s.platform().name().equalsIgnoreCase(platform))
                    .filter(s -> s.name().toLowerCase().startsWith(typed) || s.username().toLowerCase().startsWith(typed))
                    .limit(25)
                    .map(s -> new Command.Choice(s.name(), s.username()))
                    .toList();
            event.replyChoices(choices).queue();
        } catch (Exception e) {
            event.replyChoices(List.of()).queue();
        }
    }

    private String add(Guild guild, Platform platform, String input, boolean videos, boolean shorts) throws Exception {
        StreamProvider provider = providers.get(platform);
        if (provider == null) {
            return platform.getDisplayName() + " is not configured on this bot (missing API credentials).";
        }
        Optional<Channel> channel = provider.resolve(input);
        if (channel.isEmpty()) {
            return "Couldn't find a " + platform.getDisplayName() + " channel for `" + input + "`.";
        }
        String name = channel.get().displayName();
        boolean youtube = platform == Platform.YOUTUBE;
        if (!db.addStreamer(guild.getIdLong(), platform, channel.get(), !youtube || videos, !youtube || shorts)) {
            return "**" + name + "** on " + platform.getDisplayName() + " is already tracked.";
        }
        String reply = "Now tracking **" + name + "** on " + platform.getDisplayName()
                + (youtube ? " (" + youtubeFeatures(videos, shorts) + ")" : "") + ".";
        if (db.getSettings(guild.getIdLong()).channelId() == null) {
            reply += "\nSet a notification channel with `/stream channel` so I know where to post.";
        }
        return reply;
    }

    private String remove(Guild guild, Platform platform, String nameOrId) throws Exception {
        return db.removeStreamer(guild.getIdLong(), platform, nameOrId)
                .map(s -> "Stopped tracking **" + s.name() + "** on " + platform.getDisplayName() + ".")
                .orElse("`" + nameOrId + "` on " + platform.getDisplayName() + " isn't tracked.");
    }

    private static String youtubeFeatures(boolean videos, boolean shorts) {
        List<String> features = new ArrayList<>(List.of("live"));
        if (videos) {
            features.add("videos");
        }
        if (shorts) {
            features.add("Shorts");
        }
        return String.join(", ", features);
    }

    private String list(Guild guild) throws Exception {
        GuildSettings settings = db.getSettings(guild.getIdLong());
        List<TrackedStreamer> streamers = db.getStreamers(guild.getIdLong());

        StringBuilder sb = new StringBuilder();
        sb.append("**Channel:** ").append(settings.channelId() == null ? "not set" : "<#" + settings.channelId() + ">").append('\n');
        sb.append("**Ping role:** ").append(settings.pingRoleId() == null ? "none" : "<@&" + settings.pingRoleId() + ">").append("\n\n");
        if (streamers.isEmpty()) {
            sb.append("No streamers tracked yet. Add one with `/stream add`.");
        } else {
            for (Platform platform : Platform.values()) {
                List<String> names = streamers.stream()
                        .filter(s -> s.platform() == platform)
                        .map(s -> "[" + s.name() + "](<" + platform.channelUrl(s.username()) + ">)"
                                + (platform == Platform.YOUTUBE && !(s.notifyVideos() && s.notifyShorts())
                                ? " (" + youtubeFeatures(s.notifyVideos(), s.notifyShorts()) + ")" : ""))
                        .toList();
                if (!names.isEmpty()) {
                    sb.append("**").append(platform.getDisplayName()).append(":** ").append(String.join(", ", names)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private String channel(Guild guild, GuildMessageChannel channel) throws Exception {
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            return "I need **View Channel**, **Send Messages** and **Embed Links** in " + channel.getAsMention() + ".";
        }
        db.setChannel(guild.getIdLong(), channel.getIdLong());
        return "Notifications will be posted in " + channel.getAsMention() + ".";
    }

    private String role(Guild guild, Role role) throws Exception {
        if (role == null) {
            db.setPingRole(guild.getIdLong(), null);
            return "Role pings disabled.";
        }
        if (role.isPublicRole()) {
            return "Pinging @everyone isn't supported; pick a specific role.";
        }
        db.setPingRole(guild.getIdLong(), role.getIdLong());
        String reply = "Notifications will ping " + role.getAsMention() + ".";
        if (!role.isMentionable() && !guild.getSelfMember().hasPermission(Permission.MESSAGE_MENTION_EVERYONE)) {
            reply += "\n⚠️ That role isn't mentionable and I lack **Mention @everyone, @here, and All Roles**, so the ping won't notify anyone.";
        }
        return reply;
    }

    private String test(Guild guild) throws Exception {
        GuildSettings settings = db.getSettings(guild.getIdLong());
        if (settings.channelId() == null) {
            return "Set a notification channel first with `/stream channel`.";
        }
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, settings.channelId());
        if (channel == null) {
            return "The configured channel no longer exists. Set a new one with `/stream channel`.";
        }
        String selfName = guild.getSelfMember().getEffectiveName();
        StreamInfo sample = new StreamInfo(Platform.TWITCH, "twitch", selfName,
                "This is a test notification", "Just Chatting", 1234,
                TwitchProvider.PREVIEW_PLACEHOLDER, guild.getSelfMember().getEffectiveAvatarUrl(),
                "https://static-cdn.jtvnw.net/ttv-boxart/509658-285x380.jpg", Instant.now(), null);
        channel.sendMessage(EmbedFactory.liveMessage(sample, settings.pingRoleId(), List.of(sample.game()))).complete();
        return "Sent a test notification to " + channel.getAsMention() + ".";
    }

    private static Platform platformOption(SlashCommandInteractionEvent event) {
        return Platform.fromString(event.getOption("platform", OptionMapping::getAsString));
    }

    /**
     * Raw input; each provider normalizes it (YouTube channel IDs are case-sensitive).
     */
    private static String usernameOption(SlashCommandInteractionEvent event) {
        return event.getOption("username", OptionMapping::getAsString).trim();
    }
}
