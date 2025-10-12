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
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String token;          // persönlicher Discogs-User-Token
    private final String userAgent;      // z. B. "VinylMatch/1.0 (+kontakt)"

    // Sehr einfacher In-Memory-Cache (Artist|Album|Year -> URI)
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public DiscogsService(String token, String userAgent) {
        this.token = token;
        this.userAgent = (userAgent == null || userAgent.isBlank()) ? "VinylMatch/1.0" : userAgent;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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
        if (barcode != null && !barcode.isBlank()) {
            try {
                Optional<String> byCode = searchByBarcode(barcode);
                if (byCode.isPresent()) return byCode;
            } catch (Exception ignored) {}
        }
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        final String origArtist = artist == null ? null : artist.trim();
        final String origAlbum = album == null ? null : album.trim();
        final String origTrack = trackTitle == null ? null : trackTitle.trim();
        final Integer year = releaseYear;
        final String cacheKey = (origArtist == null ? "" : origArtist) + "|" + (origAlbum == null ? "" : origAlbum) + "|" + (year == null ? "" : year);

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
            if (r.isPresent()) { cache.put(cacheKey, r.get()); return r; }

            // Pass B: q-Suche (leicht normalisiert)
            String lightAlbum = normalizeTitleLevel(origAlbum, NormLevel.LIGHT);
            String q2 = ((artistStrict != null) ? artistStrict : "") + " " + ((lightAlbum != null) ? lightAlbum : "");
            r = searchOnceQ(q2, year, artistStrict, origAlbum);
            if (r.isPresent()) { cache.put(cacheKey, r.get()); return r; }

            // Strukturierte Fallbacks (minimiert)
            // Master mit Jahr
            r = searchOnce(artistStrict, origAlbum, year, null, true);
            if (r.isPresent()) { cache.put(cacheKey, r.get()); return r; }
            // Release mit Jahr
            r = searchOnce(artistStrict, origAlbum, year, null, false);
            if (r.isPresent()) { cache.put(cacheKey, r.get()); return r; }
            // Master ohne Jahr
            r = searchOnce(artistStrict, origAlbum, null, null, true);
            if (r.isPresent()) { cache.put(cacheKey, r.get()); return r; }
            // Release ohne Jahr
            r = searchOnce(artistStrict, origAlbum, null, null, false);
            if (r.isPresent()) { cache.put(cacheKey, r.get()); return r; }

            // Fallback: Web-Suche auf Discogs öffnen (mindestens zur passenden Suchseite)
            String fallback = buildWebSearchUrl(artistStrict, origAlbum, year);
            cache.put(cacheKey, fallback);
            return Optional.of(fallback);
        } catch (Exception e) {
            // Bei Fehlern ebenfalls Fallback-Link zurückgeben
            String fallback = buildWebSearchUrl(normalizeArtistLevel(artist, NormLevel.HEAVY), album, releaseYear);
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
                t = removeParenthesesContent(t);
                t = t.replace("&", "and");
                t = canonicalizeWhitespace(t);
                return t.trim();
            default:
                return t.trim();
        }
    }

    private static String normalizeArtistLevel(String artist, NormLevel level) {
        if (artist == null) return null;
        String a = artist;
        switch (level) {
            case RAW:
                return a.trim();
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

    private static String removeParenthesesContent(String t) {
        if (t == null) return null;
        return t.replaceAll("\\s*\\([^)]*\\)", "");
    }
}
