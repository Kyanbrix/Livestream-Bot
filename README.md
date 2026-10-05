# LiveStreamBot

A Discord bot that posts a notification when Twitch, Kick or YouTube streamers go live. It updates the post while the stream runs and marks it as ended when the stream goes offline. For YouTube channels it also announces new videos and Shorts.

## Setup

1. **Discord**: create an application at https://discord.com/developers/applications, add a bot and copy its token. Invite it with the `bot` and `applications.commands` scopes and the **View Channel**, **Send Messages** and **Embed Links** permissions. To ping a role that isn't mentionable, the bot also needs **Mention @everyone, @here, and All Roles**.
2. **Twitch**: register an app at https://dev.twitch.tv/console/apps (any OAuth redirect URL, e.g. `http://localhost`) and copy the Client ID and Secret.
3. **Kick**: create an app at https://kick.com/settings/developer and copy the Client ID and Secret.
4. **YouTube**: in the [Google Cloud Console](https://console.cloud.google.com/), create a project, enable **YouTube Data API v3**, then create an API key under *Credentials*. Restrict the key to the YouTube Data API v3. The free quota (10,000 units a day) is enough for dozens of channels, because the bot only uses 1-unit calls.

## Running

```bash
export DISCORD_TOKEN=...
export TWITCH_CLIENT_ID=...
export TWITCH_CLIENT_SECRET=...
export KICK_CLIENT_ID=...
export KICK_CLIENT_SECRET=...
export YOUTUBE_API_KEY=...

mvn package
java -jar target/LiveStreamBot-1.0-SNAPSHOT.jar
```

Optional settings:

| Variable                | Default            | Description                     |
|-------------------------|--------------------|---------------------------------|
| `DB_PATH`               | `livestreambot.db` | SQLite file for settings/state  |
| `POLL_INTERVAL_SECONDS` | `60`               | How often platforms are checked |

If you leave out a platform's credentials, that platform is turned off.

## Commands

You need the **Manage Server** permission to use these commands.

| Command | Description |
|---------|-------------|
| `/stream add <platform> <username> [channel] [role] [no-ping] [videos] [shorts]` | Start tracking a streamer. `channel` and `role` set where this streamer posts and who it pings (otherwise the server defaults are used). `no-ping:true` posts without pinging anyone, even if the server has a default role. YouTube takes an `@handle`, channel URL or channel ID, and also announces new videos and Shorts unless you set `videos:false` or `shorts:false` |
| `/stream edit <platform> <username> [channel] [role] [no-ping] [videos] [shorts] [reset]` | Change a tracked streamer's channel, role or YouTube options. `no-ping:true` turns its pings off and `no-ping:false` goes back to the default role. `reset` goes back to the server defaults |
| `/stream remove <platform> <username>` | Stop tracking a streamer |
| `/stream list` | Show tracked streamers, their channels and roles, and the server defaults |
| `/stream channel <channel>` | Default channel for streamers without their own |
| `/stream role [role]` | Default role to ping for streamers without their own, or leave empty to turn off default pings |
| `/stream test [platform] [username]` | Post a sample notification, either for a specific streamer or using the server defaults |

### YouTube notes

- New videos and Shorts are detected from each channel's public feed. The first check records the channel's existing videos without announcing them, and only videos posted in the last 48 hours are ever announced.
- A live stream is detected once it appears in the channel's feed. That's usually within a minute or two of starting, but YouTube sometimes delays the feed by a few minutes.
- When a YouTube stream ends, the post's button links to the recording if the streamer kept it.

## Deploy (Docker on DigitalOcean)

The image is built on the droplet. It uses a trimmed Java runtime with only the modules the bot needs, on Alpine. The container is limited to 256 MB of RAM, runs as a non-root user with a read-only filesystem, and restarts automatically if it crashes.

1. **Create a droplet**: Ubuntu, 1 GB RAM. Install Docker:
   ```bash
   curl -fsSL https://get.docker.com | sh
   ```
   (Or pick the "Docker" image from the DigitalOcean Marketplace.)

2. **Optional: add swap** so the first Maven build has extra memory to fall back on:
   ```bash
   fallocate -l 1G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
   echo '/swapfile none swap sw 0 0' >> /etc/fstab
   ```

3. **Copy the project** to the droplet, either with `git clone <your-repo> /opt/livestreambot` or from your machine:
   ```bash
   rsync -av --exclude target --exclude .idea --exclude .git ./ root@YOUR_DROPLET_IP:/opt/livestreambot
   ```

4. **Configure and start**:
   ```bash
   cd /opt/livestreambot
   cp .env.example .env && nano .env   # fill in your tokens
   docker compose up -d --build
   ```

### Operating

| Task              | Command                                                          |
|-------------------|------------------------------------------------------------------|
| Follow logs       | `docker compose logs -f`                                         |
| Memory / CPU      | `docker stats livestreambot`                                     |
| Restart           | `docker compose restart`                                         |
| Update            | `git pull` (or rsync again), then `docker compose up -d --build` |
| Back up database  | `docker compose cp bot:/data/livestreambot.db ./backup.db`       |
| Free disk space   | `docker image prune -f && docker builder prune -f`               |

Settings and live-stream state are kept in the `bot-data` Docker volume, so rebuilding or updating doesn't lose them. Don't run `docker compose down -v` unless you want to wipe them.

### Memory tuning

The JVM flags are set in the `Dockerfile` (`JAVA_OPTS`): Serial GC, a 96 MB heap limit, a lighter JIT compiler, and a class-data archive saved in `/data` so later starts are faster and use less memory. To change them, override `JAVA_OPTS` under `environment:` in `docker-compose.yml` and copy the full value, because the override replaces the defaults. If `docker stats` shows memory near the 256 MB limit, raise `-Xmx` and `mem_limit` together.
