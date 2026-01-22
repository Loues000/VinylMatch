package com.hctamlyniv.discogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.discogs.model.CurationCandidate;
import com.hctamlyniv.discogs.model.DiscogsProfile;
import com.hctamlyniv.discogs.model.WishlistEntry;
import com.hctamlyniv.discogs.model.WishlistResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DiscogsApiClient {

    private static final Logger log = LoggerFactory.getLogger(DiscogsApiClient.class);
    private static final String DEFAULT_API_BASE = "https://api.discogs.com";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String token;
    private final String userAgent;
    private final String apiBase;

    public DiscogsApiClient(HttpClient http, ObjectMapper mapper, String token, String userAgent) {
        this(http, mapper, token, userAgent, DEFAULT_API_BASE);
    }

    public DiscogsApiClient(HttpClient http, ObjectMapper mapper, String token, String userAgent, String apiBase) {
        this.http = http;
        this.mapper = mapper;
        this.token = token;
        this.userAgent = userAgent;
        this.apiBase = (apiBase == null || apiBase.isBlank()) ? DEFAULT_API_BASE : apiBase.trim();
    }

    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    public Optional<DiscogsProfile> fetchProfile() {
        if (!isConfigured()) {
            return Optional.empty();
        }
        try {
            HttpRequest req = baseRequest(URI.create(apiBase + "/oauth/identity"))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(resp.body());
            String username = root.path("username").asText(null);
            String name = root.path("name").asText(null);
            if (username == null || username.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new DiscogsProfile(username, name));
        } catch (Exception e) {
            log.debug("Discogs profile fetch failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public WishlistResult fetchWishlist(String username, int page, int perPage) {
        List<WishlistEntry> entries = new ArrayList<>();
        if (!isConfigured() || username == null || username.isBlank()) {
            return new WishlistResult(entries, 0);
        }
        try {
            String qs = "?sort=added&sort_order=desc&page=" + Math.max(1, page) + "&per_page=" + Math.max(1, Math.min(perPage, 50));
            URI uri = URI.create(apiBase + "/users/" + DiscogsUrlUtils.urlEncode(username) + "/wants" + qs);
            HttpRequest req = baseRequest(uri)
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return new WishlistResult(entries, 0);
            }
            JsonNode root = mapper.readTree(resp.body());
            int total = root.path("pagination").path("items").asInt(0);
            JsonNode wants = root.get("wants");
            if (wants != null && wants.isArray()) {
                for (JsonNode item : wants) {
                    JsonNode basic = item.get("basic_information");
                    if (basic == null) continue;

                    String title = basic.path("title").asText(null);
                    String artist = null;
                    JsonNode artists = basic.get("artists");
                    if (artists != null && artists.isArray() && artists.size() > 0) {
                        artist = artists.get(0).path("name").asText(null);
                    }
                    Integer year = basic.hasNonNull("year") ? basic.get("year").asInt() : null;
                    String thumb = DiscogsUrlUtils.sanitizeDiscogsWebUrl(basic.path("thumb").asText(null));
                    String uriStr = basic.path("resource_url").asText(null);
                    String webUrl = basic.path("uri").asText(null);
                    Integer releaseId = basic.hasNonNull("id") ? basic.get("id").asInt() : null;
                    String targetUrl = (webUrl != null && !webUrl.isBlank()) ? webUrl : uriStr;
                    if (targetUrl != null && targetUrl.startsWith("/")) {
                        targetUrl = "https://www.discogs.com" + targetUrl;
                    }
                    String safeTarget = DiscogsUrlUtils.sanitizeDiscogsWebUrl(targetUrl);
                    if (safeTarget != null) {
                        entries.add(new WishlistEntry(title, artist, year, thumb, safeTarget, releaseId));
                    }
                }
            }
            return new WishlistResult(entries, total);
        } catch (Exception e) {
            log.debug("Discogs wishlist fetch failed: {}", e.getMessage());
            return new WishlistResult(entries, 0);
        }
    }

    public boolean addToWantlist(String username, int releaseId) {
        if (!isConfigured() || username == null || username.isBlank()) {
            return false;
        }
        try {
            String body = "release_id=" + releaseId + "&notes=" + DiscogsUrlUtils.urlEncode("Added via VinylMatch");
            URI uri = URI.create(apiBase + "/users/" + DiscogsUrlUtils.urlEncode(username) + "/wants");
            HttpRequest req = baseRequest(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.debug("Discogs wantlist add failed: {}", e.getMessage());
            return false;
        }
    }

    public List<CurationCandidate> fetchCurationCandidates(String artist, String album, Integer releaseYear, String trackTitle, int limit)
            throws IOException, InterruptedException {
        if (!isConfigured()) {
            return List.of();
        }

        int max = Math.max(1, Math.min(limit, 10));

        String qArtist = DiscogsNormalizer.normalizeArtistLevel(artist, DiscogsNormalizer.NormLevel.HEAVY);
        String qAlbum = DiscogsNormalizer.normalizeTitleLevel(album, DiscogsNormalizer.NormLevel.LIGHT);
        String qTrack = DiscogsNormalizer.normalizeTitleLevel(trackTitle, DiscogsNormalizer.NormLevel.LIGHT);

        StringBuilder qs = new StringBuilder();
        if (qArtist != null && !qArtist.isBlank()) qs.append("artist=").append(DiscogsUrlUtils.urlEncode(qArtist)).append("&");
        if (qAlbum != null && !qAlbum.isBlank()) qs.append("release_title=").append(DiscogsUrlUtils.urlEncode(qAlbum)).append("&");
        if (qTrack != null && !qTrack.isBlank()) qs.append("track=").append(DiscogsUrlUtils.urlEncode(qTrack)).append("&");
        if (releaseYear != null && releaseYear > 1900 && releaseYear < 2100) qs.append("year=").append(releaseYear).append("&");
        qs.append("type=release&per_page=10&sort=relevance");

        URI uri = URI.create(apiBase + "/database/search?" + qs);
        HttpRequest req = baseRequest(uri)
                .timeout(Duration.ofSeconds(12))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            return List.of();
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.size() == 0) {
            return List.of();
        }

        List<CurationCandidate> candidates = new ArrayList<>();
        for (JsonNode node : results) {
            if (candidates.size() >= max) break;
            Integer id = node.has("id") && node.get("id").isInt() ? node.get("id").asInt() : null;
            String title = optText(node, "title");
            String thumb = optText(node, "thumb");
            Integer year = node.has("year") && node.get("year").isInt() ? node.get("year").asInt() : null;
            String country = optText(node, "country");
            String format = parseFormats(node.get("format"));
            String uriSuffix = optText(node, "uri");
            String url = uriSuffix != null ? DiscogsUrlUtils.sanitizeDiscogsWebUrl("https://www.discogs.com" + uriSuffix) : null;
            String safeThumb = DiscogsUrlUtils.sanitizeDiscogsWebUrl(thumb);
            if (url == null) {
                continue;
            }
            String artistName = optText(node, "artist");
            candidates.add(new CurationCandidate(id, title, artistName, year, country, format, safeThumb, url));
        }
        return candidates;
    }

    public Optional<String> searchByBarcode(String code) throws IOException, InterruptedException {
        if (!isConfigured() || code == null || code.isBlank()) {
            return Optional.empty();
        }
        StringBuilder qs = new StringBuilder();
        qs.append("barcode=").append(DiscogsUrlUtils.urlEncode(code));
        qs.append("&type=release");
        qs.append("&per_page=5&sort=relevance");
        URI uri = URI.create(apiBase + "/database/search?" + qs);
        HttpRequest req = baseRequest(uri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            return Optional.empty();
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.size() == 0) {
            return Optional.empty();
        }
        for (JsonNode item : results) {
            JsonNode uriNode = item.get("uri");
            if (uriNode != null && !uriNode.isNull()) {
                String uriStr = uriNode.asText();
                if (uriStr != null && !uriStr.isBlank()) {
                    if (uriStr.startsWith("/")) uriStr = "https://www.discogs.com" + uriStr;
                    return Optional.ofNullable(DiscogsUrlUtils.sanitizeDiscogsWebUrl(uriStr));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Integer> fetchMainReleaseId(int masterId) throws IOException, InterruptedException {
        if (!isConfigured()) {
            return Optional.empty();
        }
        URI uri = URI.create(apiBase + "/masters/" + masterId);
        HttpRequest req = baseRequest(uri)
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            return Optional.empty();
        }
        JsonNode root = mapper.readTree(resp.body());
        if (root.hasNonNull("main_release")) {
            return Optional.of(root.get("main_release").asInt());
        }
        return Optional.empty();
    }

    public Set<Integer> fetchWishlistReleaseIds(String username, int perPage) {
        Set<Integer> ids = new HashSet<>();
        if (!isConfigured() || username == null || username.isBlank()) {
            return ids;
        }
        try {
            String qs = "?sort=added&sort_order=desc&page=1&per_page=" + Math.max(1, Math.min(perPage, 100));
            URI uri = URI.create(apiBase + "/users/" + DiscogsUrlUtils.urlEncode(username) + "/wants" + qs);
            HttpRequest req = baseRequest(uri)
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return ids;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode wants = root.get("wants");
            if (wants != null && wants.isArray()) {
                for (JsonNode item : wants) {
                    JsonNode basic = item.get("basic_information");
                    if (basic != null && basic.hasNonNull("id")) {
                        ids.add(basic.get("id").asInt());
                    }
                }
            }
        } catch (Exception ignored) {}
        return ids;
    }

    public Set<Integer> fetchCollectionReleaseIds(String username, int perPage) {
        Set<Integer> ids = new HashSet<>();
        if (!isConfigured() || username == null || username.isBlank()) {
            return ids;
        }
        try {
            String qs = "?sort=added&sort_order=desc&page=1&per_page=" + Math.max(1, Math.min(perPage, 100));
            URI uri = URI.create(apiBase + "/users/" + DiscogsUrlUtils.urlEncode(username) + "/collection/folders/0/releases" + qs);
            HttpRequest req = baseRequest(uri)
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return ids;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode releases = root.get("releases");
            if (releases != null && releases.isArray()) {
                for (JsonNode item : releases) {
                    if (item.hasNonNull("id")) {
                        ids.add(item.get("id").asInt());
                    }
                }
            }
        } catch (Exception ignored) {}
        return ids;
    }

    public Optional<String> searchOnce(String artist, String album, Integer year, String trackTitle, boolean master)
            throws IOException, InterruptedException {
        if (!isConfigured()) {
            return Optional.empty();
        }
        StringBuilder qs = new StringBuilder();
        qs.append("type=").append(master ? "master" : "release");
        qs.append("&per_page=5&sort=relevance");
        if (artist != null && !artist.isBlank()) {
            qs.append("&artist=").append(DiscogsUrlUtils.urlEncode(artist));
        }
        if (album != null && !album.isBlank()) {
            qs.append("&release_title=").append(DiscogsUrlUtils.urlEncode(album));
        }
        if (trackTitle != null && !trackTitle.isBlank()) {
            qs.append("&track=").append(DiscogsUrlUtils.urlEncode(trackTitle));
        }
        if (year != null && year > 1900 && year < 2100) {
            qs.append("&year=").append(year);
        }

        URI uri = URI.create(apiBase + "/database/search?" + qs);

        HttpRequest req = baseRequest(uri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            return Optional.empty();
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.size() == 0) {
            return Optional.empty();
        }

        for (JsonNode item : results) {
            JsonNode uriNode = item.get("uri");
            if (uriNode != null && !uriNode.isNull()) {
                String uriStr = uriNode.asText();
                if (uriStr != null && !uriStr.isBlank()) {
                    if (uriStr.startsWith("/")) {
                        uriStr = "https://www.discogs.com" + uriStr;
                    }
                    return Optional.ofNullable(DiscogsUrlUtils.sanitizeDiscogsWebUrl(uriStr));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<String> searchOnceQ(String q, Integer year, String expectedArtist, String expectedAlbum)
            throws IOException, InterruptedException {
        if (!isConfigured()) {
            return Optional.empty();
        }
        if ((q == null || q.isBlank()) && (expectedArtist == null || expectedAlbum == null)) {
            return Optional.empty();
        }
        StringBuilder qs = new StringBuilder();
        if (q != null && !q.isBlank()) {
            qs.append("q=").append(DiscogsUrlUtils.urlEncode(q));
        } else {
            qs.append("q=").append(DiscogsUrlUtils.urlEncode((expectedArtist == null ? "" : expectedArtist) + " " + (expectedAlbum == null ? "" : expectedAlbum)));
        }
        qs.append("&per_page=10&sort=relevance");
        if (year != null && year > 1900 && year < 2100) {
            qs.append("&year=").append(year);
        }

        URI uri = URI.create(apiBase + "/database/search?" + qs);
        HttpRequest req = baseRequest(uri)
                .timeout(Duration.ofSeconds(12))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            return Optional.empty();
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.size() == 0) {
            return Optional.empty();
        }

        String expArtist = expectedArtist == null ? null : DiscogsNormalizer.canonicalizeWhitespace(DiscogsNormalizer.stripDiacritics(expectedArtist)).toLowerCase();
        String expAlbumRaw = expectedAlbum == null ? null : DiscogsNormalizer.canonicalizeWhitespace(DiscogsNormalizer.stripDiacritics(expectedAlbum)).toLowerCase();
        String expAlbumLight = expectedAlbum == null ? null : DiscogsNormalizer.canonicalizeWhitespace(
                DiscogsNormalizer.stripDiacritics(DiscogsNormalizer.normalizeTitleLevel(expectedAlbum, DiscogsNormalizer.NormLevel.LIGHT))
        ).toLowerCase();

        String masterCandidate = null;
        String releaseCandidate = null;
        String topAnyCandidate = null;

        for (JsonNode item : results) {
            JsonNode uriNode = item.get("uri");
            if (uriNode == null || uriNode.isNull()) continue;
            String uriStr = uriNode.asText();
            if (uriStr == null || uriStr.isBlank()) continue;
            if (uriStr.startsWith("/")) uriStr = "https://www.discogs.com" + uriStr;
            uriStr = DiscogsUrlUtils.sanitizeDiscogsWebUrl(uriStr);
            if (uriStr == null) continue;

            String type = item.hasNonNull("type") ? item.get("type").asText() : "";
            String title = item.hasNonNull("title") ? item.get("title").asText() : "";
            String titleNorm = DiscogsNormalizer.canonicalizeWhitespace(DiscogsNormalizer.stripDiacritics(title)).toLowerCase();

            if (("master".equalsIgnoreCase(type) || "release".equalsIgnoreCase(type)) && topAnyCandidate == null) {
                topAnyCandidate = uriStr;
            }

            int sep = title.indexOf(" - ");
            if (sep > 0 && expArtist != null && expAlbumRaw != null) {
                String tArtist = DiscogsNormalizer.canonicalizeWhitespace(DiscogsNormalizer.stripDiacritics(title.substring(0, sep))).toLowerCase();
                String tAlbum = DiscogsNormalizer.canonicalizeWhitespace(DiscogsNormalizer.stripDiacritics(title.substring(sep + 3))).toLowerCase();
                if (tArtist.equals(expArtist) && tAlbum.equals(expAlbumRaw)) {
                    return Optional.of(uriStr);
                }
            }

            boolean artistOk = (expArtist == null) || titleNorm.contains(expArtist);
            boolean albumOk = (expAlbumLight == null) || titleNorm.contains(expAlbumLight);
            if (artistOk && albumOk) {
                if ("master".equalsIgnoreCase(type) && masterCandidate == null) masterCandidate = uriStr;
                if ("release".equalsIgnoreCase(type) && releaseCandidate == null) releaseCandidate = uriStr;
            }
        }

        if (masterCandidate != null) return Optional.of(masterCandidate);
        if (releaseCandidate != null) return Optional.of(releaseCandidate);
        if (topAnyCandidate != null) return Optional.of(topAnyCandidate);
        return Optional.empty();
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .header("Authorization", "Discogs token=" + token);
    }

    private static String parseFormats(JsonNode formatsNode) {
        if (formatsNode == null || !formatsNode.isArray()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode node : formatsNode) {
            if (node != null && node.isTextual()) {
                parts.add(node.asText());
            }
        }
        return parts.isEmpty() ? null : String.join(" • ", parts);
    }

    private static String optText(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }
}
