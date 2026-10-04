package com.github.kyanbrix;

import com.github.kyanbrix.command.CommandListener;
import com.github.kyanbrix.db.Database;
import com.github.kyanbrix.notify.StreamPoller;
import com.github.kyanbrix.platform.KickProvider;
import com.github.kyanbrix.platform.Platform;
import com.github.kyanbrix.platform.StreamProvider;
import com.github.kyanbrix.platform.TwitchProvider;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        String token = env("DISCORD_TOKEN");
        if (token == null) {
            log.error("DISCORD_TOKEN environment variable is required.");
            System.exit(1);
        }

        Map<Platform, StreamProvider> providers = new EnumMap<>(Platform.class);
        String twitchId = env("TWITCH_CLIENT_ID");
        String twitchSecret = env("TWITCH_CLIENT_SECRET");
        if (twitchId != null && twitchSecret != null) {
            providers.put(Platform.TWITCH, new TwitchProvider(twitchId, twitchSecret));
        } else {
            log.warn("TWITCH_CLIENT_ID / TWITCH_CLIENT_SECRET not set; Twitch notifications are disabled.");
        }
        String kickId = env("KICK_CLIENT_ID");
        String kickSecret = env("KICK_CLIENT_SECRET");
        if (kickId != null && kickSecret != null) {
            providers.put(Platform.KICK, new KickProvider(kickId, kickSecret));
        } else {
            log.warn("KICK_CLIENT_ID / KICK_CLIENT_SECRET not set; Kick notifications are disabled.");
        }

        Database db = new Database(envOr("DB_PATH", "livestreambot.db"));
        long interval = Long.parseLong(envOr("POLL_INTERVAL_SECONDS", "60"));

        JDA jda = JDABuilder.createLight(token, EnumSet.noneOf(GatewayIntent.class))
                .setActivity(Activity.watching("for live streams"))
                .addEventListeners(new CommandListener(db, providers))
                .build()
                .awaitReady();
        log.info("Logged in as {}", jda.getSelfUser().getAsTag());

        jda.updateCommands().addCommands(CommandListener.commandData()).queue();

        StreamPoller poller = new StreamPoller(jda, db, providers);
        poller.start(interval);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            poller.close();
            jda.shutdown();
            try {
                jda.awaitShutdown(Duration.ofSeconds(10));
                db.close();
            } catch (Exception ignored) {
            }
        }));
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String envOr(String name, String fallback) {
        String value = env(name);
        return value != null ? value : fallback;
    }
}
