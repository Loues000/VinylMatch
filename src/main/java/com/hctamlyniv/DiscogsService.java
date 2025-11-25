package com.hctamlyniv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.text.Normalizer;

/**
 * Minimaler Discogs-Client für Album-Suche.
 *
 * Nutzung:
 *   DiscogsService svc = new DiscogsService(token, "VinylMatch/1.0 (+contact)");
 *   Optional<String> uri = svc.findAlbumUri("AC/DC", "Back In Black", 1980);
 *   uri.ifPresent(System.out::println);
 */
public class DiscogsService {

    private static final String API_BASE = "https://api.discogs.com";

    private static final Path CACHE_DIR = Paths.get("cache", "discogs");
    private static final Path CACHE_FILE = CACHE_DIR.resolve("albums.json");

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String token;          // persönlicher Discogs-User-Token
    private final String userAgent;      // z. B. "VinylMatch/1.0 (+kontakt)"

    // Sehr einfacher In-Memory-Cache (Artist|Album|Year -> URI)
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Map<String, String> barcodeCache = new ConcurrentHashMap<>();
    private final ReentrantLock persistenceLock = new ReentrantLock();

    public DiscogsService(String token, String userAgent) {
        this.token = token;
        this.userAgent = (userAgent == null || userAgent.isBlank()) ? "VinylMatch/1.0" : userAgent;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        loadPersistedCache();
    }

    private void loadPersistedCache() {
        try {
            if (!Files.exists(CACHE_FILE)) {
                return;
            }
            JsonNode root = mapper.readTree(CACHE_FILE.toFile());
            JsonNode entries = root.get("entries");
            if (entries != null && entries.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = entries.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (entry.getValue() != null && !entry.getValue().isNull()) {
                        cache.put(entry.getKey(), entry.getValue().asText());
                    }
                }
            }
            JsonNode barcodes = root.get("barcodes");
            if (barcodes != null && barcodes.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = barcodes.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (entry.getValue() != null && !entry.getValue().isNull()) {
                        barcodeCache.put(entry.getKey(), entry.getValue().asText());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[Discogs] Konnte Cache nicht laden: " + e.getMessage());
        }
    }

    private void persistCache() {
        persistenceLock.lock();
        try {
            Files.createDirectories(CACHE_DIR);
            Map<String, Object> payload = new HashMap<>();
            payload.put("updatedAt", Instant.now().toString());
            payload.put("entries", new HashMap<>(cache));
            payload.put("barcodes", new HashMap<>(barcodeCache));
            mapper.writerWithDefaultPrettyPrinter().writeValue(CACHE_FILE.toFile(), payload);
        } catch (IOException e) {
            System.err.println("[Discogs] Konnte Cache nicht speichern: " + e.getMessage());
        } finally {
            persistenceLock.unlock();
        }
    }

    private void rememberResult(String cacheKey, String url, String barcode) {
        if (url == null || url.isBlank()) {
            return;
        }
        if (cacheKey != null && !cacheKey.isBlank()) {
            cache.put(cacheKey, url);
        }
        if (barcode != null && !barcode.isBlank()) {
            barcodeCache.put(barcode, url);
        }
        persistCache();
    }

    private String buildCacheKey(String artist, String album, Integer releaseYear) {
        return (artist == null ? "" : artist) + "|" + (album == null ? "" : album) + "|" + (releaseYear == null ? "" : releaseYear);
    }

    public Optional<String> peekCachedUri(String artist, String album, Integer releaseYear, String barcode) {
        if (barcode != null && !barcode.isBlank()) {
            String byBarcode = barcodeCache.get(barcode);
            if (byBarcode != null) {
                return Optional.of(byBarcode);
            }
        }
        String key = buildCacheKey(artist != null ? artist.trim() : null,
                album != null ? album.trim() : null,
                releaseYear);
        String cached = cache.get(key);
        return cached != null ? Optional.of(cached) : Optional.empty();
    }

    /**
     * Sucht die wahrscheinlich passende Discogs-Album-URI (Master bevorzugt) anhand von Artist/Album/Jahr.
     * Gibt Optional.empty() zurück, wenn kein Treffer oder kein Token vorhanden.
     */
    public Optional<String> findAlbumUri(String artist, String album, Integer releaseYear) {
        return findAlbumUri(artist, album, releaseYear, null, null);
    }

    public Optional<String> findAlbumUri(String artist, String album, Integer releaseYear, String trackTitle) {
        return findAlbumUri(artist, album, releaseYear, trackTitle, null);
    }

    public Optional<String> findAlbumUri(String artist, String album, Integer releaseYear, String trackTitle, String barcode) {
        final String origArtist = extractPrimaryArtist(artist);
        final String origAlbum = album == null ? null : album.trim();
        final String origTrack = trackTitle == null ? null : trackTitle.trim();
        final Integer year = releaseYear;
        final String cacheKey = buildCacheKey(origArtist, origAlbum, year);
        if (barcode != null && !barcode.isBlank()) {
            String cachedBarcodeUrl = barcodeCache.get(barcode);
            if (cachedBarcodeUrl != null) {
                return Optional.of(cachedBarcodeUrl);
            }
            if (token != null && !token.isBlank()) {
                try {
                    Optional<String> byCode = searchByBarcode(barcode);
                    if (byCode.isPresent()) {
                        String url = byCode.get();
                        rememberResult(cacheKey, url, barcode);
                        return Optional.of(url);
                    }
                } catch (Exception ignored) {}
            }
        }
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String cached = cache.get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        try {
            Optional<String> r;

            // Strenger Artist immer
            String artistStrict = normalizeArtistLevel(origArtist, NormLevel.HEAVY);

            // Pass A: q-Suche (roh)
            String q1 = ((artistStrict != null) ? artistStrict : "") + " " + ((origAlbum != null) ? origAlbum : "");
            r = searchOnceQ(q1, year, artistStrict, origAlbum);
            if (r.isPresent()) { rememberResult(cacheKey, r.get(), barcode); return r; }

            // Pass B: q-Suche (leicht normalisiert)
            String lightAlbum = normalizeTitleLevel(origAlbum, NormLevel.LIGHT);
            String q2 = ((artistStrict != null) ? artistStrict : "") + " " + ((lightAlbum != null) ? lightAlbum : "");
            r = searchOnceQ(q2, year, artistStrict, origAlbum);
            if (r.isPresent()) { rememberResult(cacheKey, r.get(), barcode); return r; }

            // Strukturierte Fallbacks (minimiert)
            // Master mit Jahr
            r = searchOnce(artistStrict, origAlbum, year, null, true);
            if (r.isPresent()) { rememberResult(cacheKey, r.get(), barcode); return r; }
            // Release mit Jahr
            r = searchOnce(artistStrict, origAlbum, year, null, false);
            if (r.isPresent()) { rememberResult(cacheKey, r.get(), barcode); return r; }
            // Master ohne Jahr
            r = searchOnce(artistStrict, origAlbum, null, null, true);
            if (r.isPresent()) { rememberResult(cacheKey, r.get(), barcode); return r; }
            // Release ohne Jahr
            r = searchOnce(artistStrict, origAlbum, null, null, false);
            if (r.isPresent()) { rememberResult(cacheKey, r.get(), barcode); return r; }

            // Fallback: Web-Suche auf Discogs öffnen (mindestens zur passenden Suchseite)
            String fallback = buildWebSearchUrl(artistStrict, origAlbum, year);
            rememberResult(cacheKey, fallback, barcode);
            return Optional.of(fallback);
        } catch (Exception e) {
            // Bei Fehlern ebenfalls Fallback-Link zurückgeben
            String fallback = buildWebSearchUrl(normalizeArtistLevel(artist, NormLevel.HEAVY), album, releaseYear);
            rememberResult(cacheKey, fallback, barcode);
            return Optional.of(fallback);
        }
    }

    private Optional<String> searchByBarcode(String code) throws IOException, InterruptedException {
        StringBuilder qs = new StringBuilder();
        qs.append("barcode=").append(url(code));
        qs.append("&type=release");
        qs.append("&per_page=5&sort=relevance");
        URI uri = URI.create(API_BASE + "/database/search?" + qs);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .header("Authorization", "Discogs token=" + token)
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
                    return Optional.of(uriStr);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> searchOnce(String artist, String album, Integer year, String trackTitle, boolean master) throws IOException, InterruptedException {
        StringBuilder qs = new StringBuilder();
        qs.append("type=").append(master ? "master" : "release");
        qs.append("&per_page=5&sort=relevance");
        // artist/release_title URL-encoden
        if (artist != null && !artist.isBlank()) {
            qs.append("&artist=").append(url(artist));
        }
        if (album != null && !album.isBlank()) {
            qs.append("&release_title=").append(url(album));
        }
        if (trackTitle != null && !trackTitle.isBlank()) {
            qs.append("&track=").append(url(trackTitle));
        }
        if (year != null && year > 1900 && year < 2100) {
            qs.append("&year=").append(year);
        }

        URI uri = URI.create(API_BASE + "/database/search?" + qs);

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .header("Authorization", "Discogs token=" + token)
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

        // Heuristik: erster sinnvoller Treffer mit uri
        for (JsonNode item : results) {
            JsonNode uriNode = item.get("uri");
            if (uriNode != null && !uriNode.isNull()) {
                String uriStr = uriNode.asText();
                if (uriStr != null && !uriStr.isBlank()) {
                    // Discogs liefert hier oft relative Pfade wie "/release/…" oder "/master/…"
                    // Wir machen daraus eine absolute Web-URL.
                    if (uriStr.startsWith("/")) {
                        uriStr = "https://www.discogs.com" + uriStr;
                    }
                    return Optional.of(uriStr);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> searchOnceQ(String q, Integer year, String expectedArtist, String expectedAlbum) throws IOException, InterruptedException {
        if ((q == null || q.isBlank()) && (expectedArtist == null || expectedAlbum == null)) {
            return Optional.empty();
        }
        StringBuilder qs = new StringBuilder();
        if (q != null && !q.isBlank()) {
            qs.append("q=").append(url(q));
        } else {
            // Fallback: baue q aus Artist+Album
            qs.append("q=").append(url((expectedArtist == null ? "" : expectedArtist) + " " + (expectedAlbum == null ? "" : expectedAlbum)));
        }
        qs.append("&per_page=10&sort=relevance");
        if (year != null && year > 1900 && year < 2100) {
            qs.append("&year=").append(year);
        }

        URI uri = URI.create(API_BASE + "/database/search?" + qs);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .header("Authorization", "Discogs token=" + token)
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

        String expArtist = expectedArtist == null ? null : canonicalizeWhitespace(stripDiacritics(expectedArtist)).toLowerCase();
        String expAlbumRaw = expectedAlbum == null ? null : canonicalizeWhitespace(stripDiacritics(expectedAlbum)).toLowerCase();
        String expAlbumLight = expectedAlbum == null ? null : canonicalizeWhitespace(stripDiacritics(normalizeTitleLevel(expectedAlbum, NormLevel.LIGHT))).toLowerCase();

        String masterCandidate = null;
        String releaseCandidate = null;
        String topAnyCandidate = null;

        for (JsonNode item : results) {
            JsonNode uriNode = item.get("uri");
            if (uriNode == null || uriNode.isNull()) continue;
            String uriStr = uriNode.asText();
            if (uriStr == null || uriStr.isBlank()) continue;
            if (uriStr.startsWith("/")) uriStr = "https://www.discogs.com" + uriStr;

            String type = item.hasNonNull("type") ? item.get("type").asText() : "";
            String title = item.hasNonNull("title") ? item.get("title").asText() : "";
            String titleNorm = canonicalizeWhitespace(stripDiacritics(title)).toLowerCase();

            // Merke den ersten master/release-Treffer (Top-Relevanz), falls nichts genaueres matcht
            if (("master".equalsIgnoreCase(type) || "release".equalsIgnoreCase(type)) && topAnyCandidate == null) {
                topAnyCandidate = uriStr;
            }

            // Soforttreffer: exakte Artist- und Album-Übereinstimmung in "Artist - Album"
            int sep = title.indexOf(" - ");
            if (sep > 0 && expArtist != null && expAlbumRaw != null) {
                String tArtist = canonicalizeWhitespace(stripDiacritics(title.substring(0, sep))).toLowerCase();
                String tAlbum  = canonicalizeWhitespace(stripDiacritics(title.substring(sep + 3))).toLowerCase();
                if (tArtist.equals(expArtist) && tAlbum.equals(expAlbumRaw)) {
                    return Optional.of(uriStr);
                }
            }

            // Kandidaten sammeln: beide Tokens enthalten
            boolean artistOk = (expArtist == null) || titleNorm.contains(expArtist);
            boolean albumOk  = (expAlbumLight == null) || titleNorm.contains(expAlbumLight);
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

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String buildWebSearchUrl(String artist, String album, Integer year) {
        String a = artist == null ? "" : artist;
        String b = album == null ? "" : album;
        String q = (a + " " + b).trim();
        StringBuilder sb = new StringBuilder("https://www.discogs.com/search/?q=")
                .append(url(q))
                .append("&type=all&sort=relevance");
        if (year != null && year > 1900 && year < 2100) {
            sb.append("&year=").append(year);
        }
        return sb.toString();
    }

    // Sehr einfache Normalisierung: Klammerzusätze entfernen und Trim
    private static String normalizeTitle(String title) {
        if (title == null) return null;
        String t = stripDiacritics(title).trim();
        // Entferne (Deluxe...), (Remastered ...), (Expanded ...), etc.
        t = t.replaceAll("\\s*\\([^)]*\\)", "");
        // Entferne Suffixe wie "- Remaster", "- Deluxe"
        t = t.replaceAll("\\s*-\\s*(?i)(Remaster(ed)?|Deluxe|Expanded|Anniversary|Edition|Remix|Reissue).*$", "");
        // Vereinheitliche Verbindungszeichen
        t = t.replace("&", "and");
        t = t.replaceAll("\\s+", " ");
        return t.trim();
    }

    private static String normalizeArtist(String artist) {
        if (artist == null) return null;
        String a = stripDiacritics(artist).trim();
        // Entferne häufige Feature-Kennzeichnungen aus dem Suchstring
        a = a.replaceAll("(?i)\\s+(feat\\.|featuring|with|x)\\s+.*$", "");
        a = a.replace("&", "and");
        a = a.replaceAll("\\s+", " ");
        return a.trim();
    }

    private static String stripDiacritics(String s) {
        if (s == null) return null;
        String norm = Normalizer.normalize(s, Normalizer.Form.NFD);
        return norm.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    private static String extractPrimaryArtist(String artist) {
        if (artist == null) return null;
        String[] tokens = artist.split("\\s*(?:,|;|/|&|\\+|\\band\\b|\\s+(?:feat\\.?|featuring|ft\\.?|with|x)\\s+)\\s*", -1);
        if (tokens.length == 0) {
            return artist.trim();
        }
        String primary = tokens[0].trim();
        return primary.isEmpty() ? artist.trim() : primary;
    }

    private enum NormLevel { RAW, LIGHT, HEAVY }

    private static String normalizeTitleLevel(String title, NormLevel level) {
        if (title == null) return null;
        String t = title;
        switch (level) {
            case RAW:
                return t.trim();
            case LIGHT:
                t = stripDiacritics(t);
                t = canonicalizeWhitespace(t);
                return t.trim();
            case HEAVY:
                t = stripDiacritics(t);
                t = removeMarketingSuffixes(t);
                t = removeBracketedContent(t);
                t = t.replace("&", "and");
                t = canonicalizeWhitespace(t);
                return t.trim();
            default:
                return t.trim();
        }
    }

    private static String normalizeArtistLevel(String artist, NormLevel level) {
        if (artist == null) return null;
        String a = extractPrimaryArtist(artist);
        switch (level) {
            case RAW:
                return a == null ? null : a.trim();
            case LIGHT:
                a = stripDiacritics(a);
                a = a.replaceAll("(?i)\\s+(feat\\.|featuring|with|x)\\s+.*$", "");
                a = a.replace("&", "and");
                a = canonicalizeWhitespace(a);
                return a.trim();
            case HEAVY:
                a = stripDiacritics(a);
                a = a.replaceAll("(?i)\\s+(feat\\.|featuring|with|x)\\s+.*$", "");
                a = a.replace("&", "and");
                a = canonicalizeWhitespace(a);
                return a.trim();
            default:
                return a.trim();
        }
    }

    private static String canonicalizeWhitespace(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ");
    }

    private static String removeMarketingSuffixes(String t) {
        if (t == null) return null;
        // Entferne Suffixe wie "- Remaster", "- Deluxe", etc.
        return t.replaceAll("\\s*-\\s*(?i)(Remaster(ed)?|Deluxe|Expanded|Anniversary|Edition|Remix|Reissue).*$", "");
    }

    private static String removeBracketedContent(String t) {
        if (t == null) return null;
        return t.replaceAll("\\s*\\([^)]*\\)", "")
                .replaceAll("\\s*\\[[^]]*\\]", "")
                .replaceAll("\\s*\\{[^}]*\\}", "");
    }

    private static String removeParenthesesContent(String t) {
        if (t == null) return null;
        return t.replaceAll("\\s*\\([^)]*\\)", "");
    }
}
