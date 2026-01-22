package com.hctamlyniv.discogs;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class DiscogsUrlUtils {

    private DiscogsUrlUtils() {}

    public static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static String buildWebSearchUrl(String artist, String album, Integer year) {
        String a = artist == null ? "" : artist;
        String b = album == null ? "" : album;
        String q = (a + " " + b).trim();
        StringBuilder sb = new StringBuilder("https://www.discogs.com/search/?q=")
                .append(urlEncode(q))
                .append("&type=all&sort=relevance");
        if (year != null && year > 1900 && year < 2100) {
            sb.append("&year=").append(year);
        }
        return sb.toString();
    }

    public static String sanitizeDiscogsWebUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                return null;
            }
            String lowerHost = host.toLowerCase();
            if (!lowerHost.equals("discogs.com") && !lowerHost.endsWith(".discogs.com")) {
                return null;
            }
            return uri.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static Optional<Integer> resolveReleaseIdFromUrl(String url) {
        String normalized = sanitizeDiscogsWebUrl(url);
        if (normalized == null) {
            return Optional.empty();
        }
        String lower = normalized.toLowerCase();
        try {
            if (lower.contains("/release/")) {
                String[] tokens = lower.split("/release/");
                if (tokens.length > 1) {
                    String part = tokens[1].split("[^0-9]")[0];
                    if (!part.isBlank()) {
                        return Optional.of(Integer.parseInt(part));
                    }
                }
            }
            if (lower.contains("/master/")) {
                String[] tokens = lower.split("/master/");
                if (tokens.length > 1) {
                    String part = tokens[1].split("[^0-9]")[0];
                    if (!part.isBlank()) {
                        return Optional.of(Integer.parseInt(part));
                    }
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }
}

