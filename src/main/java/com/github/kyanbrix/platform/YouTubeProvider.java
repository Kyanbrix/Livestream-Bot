package com.github.kyanbrix.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube via each channel's free RSS feed plus the YouTube Data API v3.
 * <p>
 * Quota: the API allows 10,000 units a day. Only 1-unit calls are used ({@code videos.list} with up to
 * 50 IDs, {@code channels.list}); never {@code search.list}, which costs 100 units per call.
 */
public class YouTubeProvider extends HttpSupport implements StreamProvider {

    private static final String API = "https://www.googleapis.com/youtube/v3";
    private static final String FEED = "https://www.youtube.com/feeds/videos.xml?channel_id=";
    private static final String USER_AGENT = "LiveStreamBot/1.0";
    private static final int BATCH_SIZE = 50;

    private static final String NS_ATOM = "http://www.w3.org/2005/Atom";
    private static final String NS_YT = "http://www.youtube.com/xml/schemas/2015";
    private static final String NS_MEDIA = "http://search.yahoo.com/mrss/";

    private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");
    private static final Pattern HANDLE = Pattern.compile("@([A-Za-z0-9._-]{3,30})");

    private final String apiKey;

    /** Videos that can never go live (regular uploads, finished streams); skipped to save quota. */
    private final Set<String> finished = ConcurrentHashMap.newKeySet();
    private final Map<String, String> avatars = new ConcurrentHashMap<>();

    public YouTubeProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public Platform platform() {
        return Platform.YOUTUBE;
    }

    /**
     * Accepts {@code @handle}, a bare handle, a channel ID ({@code UC…}) or a youtube.com channel URL.
     */
    @Override
    public Optional<Channel> resolve(String input) throws IOException, InterruptedException {
        String text = input.trim();
        String filter;
        Matcher id = CHANNEL_ID.matcher(text);
        Matcher handle = HANDLE.matcher(text);
        if (id.find()) {
            filter = "id=" + id.group();
        } else if (handle.find()) {
            filter = "forHandle=" + encode("@" + handle.group(1));
        } else if (text.matches("[A-Za-z0-9._-]{3,30}")) {
            filter = "forHandle=" + encode("@" + text);
        } else {
            return Optional.empty();
        }
        JsonNode channel = api("/channels?part=snippet&" + filter).path("items").path(0);
        if (channel.isMissingNode()) {
            return Optional.empty();
        }
        cacheAvatar(channel);
        return Optional.of(new Channel(channel.path("id").asText(), channel.path("snippet").path("title").asText()));
    }

    @Override
    public Map<String, StreamInfo> fetchLive(Collection<String> channelIds) throws IOException, InterruptedException {
        Map<String, List<YouTubeVideo>> feeds = new HashMap<>();
        Set<String> feedIds = new HashSet<>();
        for (String channelId : channelIds) {
            List<YouTubeVideo> feed = fetchFeed(channelId);
            feeds.put(channelId, feed);
            feed.forEach(v -> feedIds.add(v.videoId()));
        }
        // Forget videos that dropped out of every feed so the cache stays small.
        finished.retainAll(feedIds);

        List<String> candidates = feedIds.stream().filter(v -> !finished.contains(v)).toList();
        Map<String, JsonNode> videos = videos(candidates);

        Map<String, StreamInfo> result = new HashMap<>();
        long cacheBuster = Instant.now().getEpochSecond();
        for (var feed : feeds.entrySet()) {
            for (YouTubeVideo entry : feed.getValue()) {
                JsonNode video = videos.get(entry.videoId());
                if (video == null) {
                    continue;
                }
                JsonNode snippet = video.path("snippet");
                String state = snippet.path("liveBroadcastContent").asText("none");
                JsonNode live = video.path("liveStreamingDetails");
                if ("none".equals(state) && (live.isMissingNode() || live.has("actualEndTime"))) {
                    finished.add(entry.videoId());
                    continue;
                }
                if (!"live".equals(state) || result.containsKey(feed.getKey())) {
                    continue;
                }
                String thumbnail = bestThumbnail(snippet);
                result.put(feed.getKey(), new StreamInfo(
                        Platform.YOUTUBE,
                        feed.getKey(),
                        snippet.path("channelTitle").asText(entry.channelTitle()),
                        snippet.path("title").asText(entry.title()),
                        "",
                        live.path("concurrentViewers").asInt(),
                        thumbnail == null ? null : thumbnail + "?t=" + cacheBuster,
                        avatar(feed.getKey()),
                        null,
                        parseInstant(live.path("actualStartTime").asText(null)),
                        "https://www.youtube.com/watch?v=" + entry.videoId()
                ));
            }
        }
        return result;
    }

    /**
     * On YouTube the recording is the live video itself, once the stream has ended.
     */
    @Override
    public Optional<Vod> findVod(String channelId, Instant startedAt) throws IOException, InterruptedException {
        List<String> ids = fetchFeed(channelId).stream().map(YouTubeVideo::videoId).toList();
        for (JsonNode video : videos(ids).values()) {
            JsonNode live = video.path("liveStreamingDetails");
            if (live.has("actualEndTime")
                    && StreamProvider.sameBroadcast(parseInstant(live.path("actualStartTime").asText(null)), startedAt)) {
                return Optional.of(new Vod("https://www.youtube.com/watch?v=" + video.path("id").asText(),
                        bestThumbnail(video.path("snippet"))));
            }
        }
        return Optional.empty();
    }

    /**
     * Fetches a channel's 15 most recent uploads, newest first. Free: no API key or quota.
     *
     * @return an empty list if the channel no longer exists
     */
    public List<YouTubeVideo> fetchFeed(String channelId) throws IOException, InterruptedException {
        try {
            return parseFeed(channelId, getPublicText(FEED + encode(channelId), USER_AGENT, "application/atom+xml"));
        } catch (HttpStatusException e) {
            if (e.status() == 404) {
                return List.of();
            }
            throw e;
        }
    }

    public static List<YouTubeVideo> parseFeed(String channelId, String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // throw on errors instead of printing to stderr
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList entries = doc.getElementsByTagNameNS(NS_ATOM, "entry");
            List<YouTubeVideo> videos = new ArrayList<>();
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                String videoId = text(entry, NS_YT, "videoId");
                if (videoId == null) {
                    continue;
                }
                Element link = (Element) entry.getElementsByTagNameNS(NS_ATOM, "link").item(0);
                Element author = (Element) entry.getElementsByTagNameNS(NS_ATOM, "author").item(0);
                Element thumbnail = (Element) entry.getElementsByTagNameNS(NS_MEDIA, "thumbnail").item(0);
                videos.add(new YouTubeVideo(
                        videoId,
                        channelId,
                        author != null ? text(author, NS_ATOM, "name") : null,
                        text(entry, NS_ATOM, "title"),
                        parseInstant(text(entry, NS_ATOM, "published")),
                        thumbnail != null ? blankToNull(thumbnail.getAttribute("url")) : null,
                        link != null && link.getAttribute("href").contains("/shorts/")
                ));
            }
            return videos;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not parse YouTube feed for " + channelId, e);
        }
    }

    /**
     * Keeps only real uploads (videos and Shorts), dropping live streams, premieres, their recordings
     * and anything that isn't public. Also swaps in the best available thumbnail.
     */
    public List<YouTubeVideo> classifyUploads(List<YouTubeVideo> entries) throws IOException, InterruptedException {
        Map<String, JsonNode> videos = videos(entries.stream().map(YouTubeVideo::videoId).toList());
        List<YouTubeVideo> uploads = new ArrayList<>();
        for (YouTubeVideo entry : entries) {
            JsonNode video = videos.get(entry.videoId());
            if (video == null || video.has("liveStreamingDetails")
                    || !"none".equals(video.path("snippet").path("liveBroadcastContent").asText("none"))) {
                continue;
            }
            String thumbnail = bestThumbnail(video.path("snippet"));
            uploads.add(thumbnail != null ? entry.withThumbnail(thumbnail) : entry);
        }
        return uploads;
    }

    /**
     * @return the channel's avatar URL (cached), or null if it can't be fetched
     */
    public String avatar(String channelId) {
        String cached = avatars.get(channelId);
        if (cached != null) {
            return cached;
        }
        try {
            api("/channels?part=snippet&id=" + encode(channelId)).path("items").forEach(this::cacheAvatar);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // cosmetic only
        }
        return avatars.get(channelId);
    }

    private void cacheAvatar(JsonNode channel) {
        String url = blankToNull(channel.path("snippet").path("thumbnails").path("default").path("url").asText(null));
        if (url != null) {
            avatars.put(channel.path("id").asText(), url);
        }
    }

    private Map<String, JsonNode> videos(List<String> ids) throws IOException, InterruptedException {
        Map<String, JsonNode> result = new HashMap<>();
        for (List<String> batch : batches(ids, BATCH_SIZE)) {
            api("/videos?part=snippet,liveStreamingDetails&id=" + encode(String.join(",", batch)))
                    .path("items").forEach(v -> result.put(v.path("id").asText(), v));
        }
        return result;
    }

    private JsonNode api(String pathAndQuery) throws IOException, InterruptedException {
        try {
            return getPublicJson(API + pathAndQuery + "&key=" + encode(apiKey), USER_AGENT);
        } catch (HttpStatusException e) {
            // The message contains the request URL; never let the API key reach the logs.
            throw new IOException(e.getMessage().replace(encode(apiKey), "***"));
        }
    }

    private static String bestThumbnail(JsonNode snippet) {
        JsonNode thumbnails = snippet.path("thumbnails");
        for (String size : List.of("maxres", "standard", "high", "medium", "default")) {
            String url = blankToNull(thumbnails.path(size).path("url").asText(null));
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    private static String text(Element parent, String ns, String name) {
        NodeList nodes = parent.getElementsByTagNameNS(ns, name);
        return nodes.getLength() == 0 ? null : blankToNull(nodes.item(0).getTextContent());
    }
}
