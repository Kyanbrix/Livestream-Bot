package com.github.kyanbrix.command;

import com.github.kyanbrix.db.Database;
import com.github.kyanbrix.db.Database.GuildSettings;
import com.github.kyanbrix.db.Database.TrackedStreamer;
import com.github.kyanbrix.notify.EmbedFactory;
import com.github.kyanbrix.notify.PostTarget;
import com.github.kyanbrix.platform.Channel;
import com.github.kyanbrix.platform.Platform;
import com.github.kyanbrix.platform.StreamInfo;
import com.github.kyanbrix.platform.StreamProvider;
import com.github.kyanbrix.platform.TwitchProvider;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
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
import java.util.Set;
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
        OptionData trackedUsername = new OptionData(OptionType.STRING, "username", "Tracked channel name", true, true);

        return Commands.slash("stream", "Manage live stream notifications")
                .setContexts(InteractionContextType.GUILD)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addSubcommands(
                        new SubcommandData("add", "Notify when a streamer goes live (YouTube: also new videos and Shorts)")
                                .addOptions(platform,
                                        new OptionData(OptionType.STRING, "username", "Channel name, @handle or channel URL", true),
                                        new OptionData(OptionType.BOOLEAN, "videos", "YouTube: announce new videos (default: yes)", false),
                                        new OptionData(OptionType.BOOLEAN, "shorts", "YouTube: announce new Shorts (default: yes)", false),
                                        streamerChannelOption(),
                                        new OptionData(OptionType.ROLE, "role", "Role to ping for this streamer (default: /stream role)", false),
                                        noPingOption()),
                        new SubcommandData("edit", "Change where a tracked streamer posts, who it pings, or what it announces")
                                .addOptions(platform, trackedUsername,
                                        streamerChannelOption(),
                                        new OptionData(OptionType.ROLE, "role", "Role to ping for this streamer", false),
                                        noPingOption(),
                                        new OptionData(OptionType.BOOLEAN, "videos", "YouTube: announce new videos", false),
                                        new OptionData(OptionType.BOOLEAN, "shorts", "YouTube: announce new Shorts", false),
                                        new OptionData(OptionType.STRING, "reset", "Go back to the server defaults", false)
                                                .addChoice("Channel", "channel")
                                                .addChoice("Role", "role")
                                                .addChoice("Channel and role", "both")),
                        new SubcommandData("remove", "Stop tracking a streamer")
                                .addOptions(platform, trackedUsername),
                        new SubcommandData("list", "Show tracked streamers and settings"),
                        new SubcommandData("channel", "Set the default channel for streamers without their own")
                                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Notification channel", true)
                                        .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS)),
                        new SubcommandData("role", "Set the default role to ping (leave empty to disable pings)")
                                .addOption(OptionType.ROLE, "role", "Role to mention", false),
                        new SubcommandData("test", "Post a sample notification to check your setup")
                                .addOptions(
                                        new OptionData(OptionType.STRING, "platform", "Test a specific streamer's channel and role", false)
                                                .addChoices(platform.getChoices()),
                                        new OptionData(OptionType.STRING, "username", "Tracked channel name", false, true))
                );
    }

    private static OptionData noPingOption() {
        return new OptionData(OptionType.BOOLEAN, "no-ping", "True: post without pinging anyone for this streamer", false);
    }

    private static OptionData streamerChannelOption() {
        return new OptionData(OptionType.CHANNEL, "channel", "Channel for this streamer's posts (default: /stream channel)", false)
                .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS);
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
                            event.getOption("shorts", true, OptionMapping::getAsBoolean),
                            channelOption(event), event.getOption("role", OptionMapping::getAsRole),
                            event.getOption("no-ping", false, OptionMapping::getAsBoolean));
                    case "edit" -> edit(guild, platformOption(event), usernameOption(event),
                            channelOption(event), event.getOption("role", OptionMapping::getAsRole),
                            event.getOption("no-ping", OptionMapping::getAsBoolean),
                            event.getOption("videos", OptionMapping::getAsBoolean),
                            event.getOption("shorts", OptionMapping::getAsBoolean),
                            event.getOption("reset", "", OptionMapping::getAsString));
                    case "remove" -> remove(guild, platformOption(event), usernameOption(event));
                    case "list" -> list(guild);
                    case "channel" -> channel(guild, channelOption(event));
                    case "role" -> role(guild, event.getOption("role", OptionMapping::getAsRole));
                    case "test" -> test(guild, event.getOption("platform", OptionMapping::getAsString),
                            event.getOption("username", OptionMapping::getAsString));
                    default -> "Unknown subcommand.";
                };
                send(hook, reply);
            } catch (Exception e) {
                log.error("/stream {} failed in {}", sub, guild.getName(), e);
                String message = String.valueOf(e.getMessage());
                send(hook, "Something went wrong: " + (message.length() > 300 ? message.substring(0, 300) + "…" : message));
            }
        });
    }

    /**
     * Sends a reply, split into several messages at line breaks if it's over Discord's length limit.
     */
    private static void send(InteractionHook hook, String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            while (line.length() > Message.MAX_CONTENT_LENGTH) { // a single line too long to fit anywhere
                flush(chunks, chunk);
                chunks.add(line.substring(0, Message.MAX_CONTENT_LENGTH));
                line = line.substring(Message.MAX_CONTENT_LENGTH);
            }
            if (chunk.length() + 1 + line.length() > Message.MAX_CONTENT_LENGTH) {
                flush(chunks, chunk);
            }
            if (!chunk.isEmpty()) {
                chunk.append('\n');
            }
            chunk.append(line);
        }
        flush(chunks, chunk);
        for (String c : chunks) {
            hook.sendMessage(c).setEphemeral(true).queue(); // follow-ups too, like the deferred reply
        }
    }

    private static void flush(List<String> chunks, StringBuilder chunk) {
        if (!chunk.toString().isBlank()) {
            chunks.add(chunk.toString());
        }
        chunk.setLength(0);
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("stream") || !Set.of("remove", "edit", "test").contains(event.getSubcommandName())
                || event.getGuild() == null) {
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

    private String add(Guild guild, Platform platform, String input, boolean videos, boolean shorts,
                       GuildMessageChannel postChannel, Role pingRole, boolean noPing) throws Exception {
        String problem = postChannel != null ? channelProblem(guild, postChannel) : null;
        if (problem == null) {
            problem = noPing && pingRole != null ? "Pick either `role` or `no-ping`, not both." : roleProblem(pingRole);
        }
        if (problem != null) {
            return problem;
        }
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
        if (!db.addStreamer(guild.getIdLong(), platform, channel.get(), !youtube || videos, !youtube || shorts,
                postChannel != null ? postChannel.getIdLong() : null,
                noPing ? TrackedStreamer.NO_PING : pingRole != null ? pingRole.getIdLong() : null)) {
            return "**" + name + "** on " + platform.getDisplayName() + " is already tracked. Use `/stream edit` to change it.";
        }
        TrackedStreamer added = db.findStreamer(guild.getIdLong(), platform, channel.get().id()).orElseThrow();
        return "Now tracking **" + name + "** on " + platform.getDisplayName()
                + (youtube ? " (" + youtubeFeatures(videos, shorts) + ")" : "") + ".\n"
                + routing(guild, added) + roleWarning(guild, pingRole);
    }

    /**
     * @param noPing true turns pings off for this streamer; false goes back to the server's default role
     */
    private String edit(Guild guild, Platform platform, String nameOrId, GuildMessageChannel postChannel, Role pingRole,
                        Boolean noPing, Boolean videos, Boolean shorts, String reset) throws Exception {
        Optional<TrackedStreamer> streamer = db.findStreamer(guild.getIdLong(), platform, nameOrId);
        if (streamer.isEmpty()) {
            return "`" + nameOrId + "` on " + platform.getDisplayName() + " isn't tracked.";
        }
        boolean clearChannel = reset.equals("channel") || reset.equals("both");
        boolean resetRole = reset.equals("role") || reset.equals("both");
        boolean disablePing = Boolean.TRUE.equals(noPing);
        String problem = postChannel != null && !clearChannel ? channelProblem(guild, postChannel) : null;
        if (problem == null && disablePing && (pingRole != null || resetRole)) {
            problem = "`no-ping:true` can't be combined with `role` or a role `reset`.";
        }
        if (problem == null && !resetRole) {
            problem = roleProblem(pingRole);
        }
        if (problem != null) {
            return problem;
        }
        if (postChannel == null && pingRole == null && noPing == null && videos == null && shorts == null && reset.isEmpty()) {
            return "Nothing to change. Pick a `channel`, `role`, `no-ping`, `videos`, `shorts` or `reset` option.";
        }
        // no-ping:false without a new role means "ping the server default again".
        boolean clearRole = resetRole || (Boolean.FALSE.equals(noPing) && pingRole == null);
        Long newRoleId = disablePing ? Long.valueOf(TrackedStreamer.NO_PING) : pingRole != null ? pingRole.getIdLong() : null;
        boolean youtube = platform == Platform.YOUTUBE;
        db.updateStreamer(guild.getIdLong(), platform, streamer.get().username(),
                postChannel != null ? postChannel.getIdLong() : null, newRoleId,
                youtube ? videos : null, youtube ? shorts : null, clearChannel, clearRole);
        TrackedStreamer updated = db.findStreamer(guild.getIdLong(), platform, streamer.get().username()).orElseThrow();
        return "Updated **" + updated.name() + "** on " + platform.getDisplayName()
                + (youtube ? " (" + youtubeFeatures(updated.notifyVideos(), updated.notifyShorts()) + ")" : "") + ".\n"
                + routing(guild, updated) + (clearRole ? "" : roleWarning(guild, pingRole));
    }

    /**
     * Describes where a streamer's posts go and who they ping, noting whether that's the server default.
     */
    private String routing(Guild guild, TrackedStreamer streamer) throws Exception {
        GuildSettings settings = db.getSettings(guild.getIdLong());
        Long channelId = PostTarget.effectiveChannelId(settings, streamer);
        Long roleId = PostTarget.effectiveRoleId(settings, streamer);
        if (channelId == null) {
            return "⚠️ No channel to post in yet: set one with `/stream edit` or a server default with `/stream channel`.";
        }
        return "Posts go to <#" + channelId + ">" + (streamer.channelId() == null ? " (server default)" : "")
                + (streamer.pingDisabled() ? " without a ping (turned off for this streamer)."
                : roleId == null ? " without a ping."
                : " and ping <@&" + roleId + ">" + (streamer.pingRoleId() == null ? " (server default)." : "."));
    }

    private static String channelProblem(Guild guild, GuildMessageChannel channel) {
        return guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
                ? null
                : "I need **View Channel**, **Send Messages** and **Embed Links** in " + channel.getAsMention() + ".";
    }

    private static String roleProblem(Role role) {
        return role != null && role.isPublicRole() ? "Pinging @everyone isn't supported; pick a specific role." : null;
    }

    private static String roleWarning(Guild guild, Role role) {
        return role != null && !role.isMentionable() && !guild.getSelfMember().hasPermission(Permission.MESSAGE_MENTION_EVERYONE)
                ? "\n⚠️ " + role.getAsMention() + " isn't mentionable and I lack **Mention @everyone, @here, and All Roles**, so the ping won't notify anyone."
                : "";
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
        sb.append("**Default channel:** ").append(settings.channelId() == null ? "not set" : "<#" + settings.channelId() + ">").append('\n');
        sb.append("**Default ping role:** ").append(settings.pingRoleId() == null ? "none" : "<@&" + settings.pingRoleId() + ">").append("\n\n");
        if (streamers.isEmpty()) {
            sb.append("No streamers tracked yet. Add one with `/stream add`.");
        } else {
            for (Platform platform : Platform.values()) {
                List<TrackedStreamer> onPlatform = streamers.stream().filter(s -> s.platform() == platform).toList();
                if (onPlatform.isEmpty()) {
                    continue;
                }
                sb.append("**").append(platform.getDisplayName()).append("**\n");
                for (TrackedStreamer s : onPlatform) {
                    sb.append("• [").append(s.name()).append("](<").append(platform.channelUrl(s.username())).append(">)");
                    if (platform == Platform.YOUTUBE && !(s.notifyVideos() && s.notifyShorts())) {
                        sb.append(" (").append(youtubeFeatures(s.notifyVideos(), s.notifyShorts())).append(")");
                    }
                    List<String> overrides = new ArrayList<>();
                    if (s.channelId() != null) {
                        overrides.add("<#" + s.channelId() + ">");
                    }
                    if (s.pingDisabled()) {
                        overrides.add("no ping");
                    } else if (s.pingRoleId() != null) {
                        overrides.add("<@&" + s.pingRoleId() + ">");
                    }
                    if (!overrides.isEmpty()) {
                        sb.append(" → ").append(String.join(" · ", overrides));
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private String channel(Guild guild, GuildMessageChannel channel) throws Exception {
        String problem = channelProblem(guild, channel);
        if (problem != null) {
            return problem;
        }
        db.setChannel(guild.getIdLong(), channel.getIdLong());
        return "Streamers without their own channel will post in " + channel.getAsMention() + ".";
    }

    private String role(Guild guild, Role role) throws Exception {
        if (role == null) {
            db.setPingRole(guild.getIdLong(), null);
            return "Role pings disabled.";
        }
        String problem = roleProblem(role);
        if (problem != null) {
            return problem;
        }
        db.setPingRole(guild.getIdLong(), role.getIdLong());
        return "Streamers without their own role will ping " + role.getAsMention() + "." + roleWarning(guild, role);
    }

    private String test(Guild guild, String platformName, String nameOrId) throws Exception {
        GuildSettings settings = db.getSettings(guild.getIdLong());
        TrackedStreamer streamer;
        if (platformName != null && nameOrId != null) {
            Optional<TrackedStreamer> found = db.findStreamer(guild.getIdLong(), Platform.fromString(platformName), nameOrId.trim());
            if (found.isEmpty()) {
                return "`" + nameOrId + "` on " + Platform.fromString(platformName).getDisplayName() + " isn't tracked.";
            }
            streamer = found.get();
        } else if (platformName != null || nameOrId != null) {
            return "Give both `platform` and `username` to test a specific streamer, or neither to test the server defaults.";
        } else {
            // No streamer: a stand-in with no overrides exercises the server defaults.
            streamer = new TrackedStreamer(guild.getIdLong(), Platform.TWITCH, "test", null, true, true, null, null);
        }
        if (PostTarget.effectiveChannelId(settings, streamer) == null) {
            return "There's no channel to post in. Set one with `/stream channel` or `/stream edit`.";
        }
        PostTarget target = PostTarget.of(guild.getJDA(), settings, streamer);
        if (target == null) {
            return "I can't post in <#" + PostTarget.effectiveChannelId(settings, streamer)
                    + ">. It may be deleted, or I'm missing **View Channel**, **Send Messages** or **Embed Links** there.";
        }
        GuildMessageChannel channel = target.channel();
        String selfName = streamer.displayName() != null ? streamer.name() : guild.getSelfMember().getEffectiveName();
        StreamInfo sample = new StreamInfo(Platform.TWITCH, "twitch", selfName,
                "This is a test notification", "Just Chatting", 1234,
                TwitchProvider.PREVIEW_PLACEHOLDER, guild.getSelfMember().getEffectiveAvatarUrl(),
                "https://static-cdn.jtvnw.net/ttv-boxart/509658-285x380.jpg", Instant.now(), null);
        channel.sendMessage(EmbedFactory.liveMessage(sample, target.pingRoleId(), List.of(sample.game()))).complete();
        return "Sent a test notification to " + channel.getAsMention() + ".";
    }

    private static GuildMessageChannel channelOption(SlashCommandInteractionEvent event) {
        OptionMapping option = event.getOption("channel");
        return option == null ? null : option.getAsChannel().asGuildMessageChannel();
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
