package com.hctamlyniv.discogs;

import java.text.Normalizer;

public final class DiscogsNormalizer {

    private DiscogsNormalizer() {}

    public enum NormLevel { RAW, LIGHT, HEAVY }

    public static String extractPrimaryArtist(String artist) {
        if (artist == null) return null;
        // Keep artist names that contain "/" (e.g. "AC/DC") intact; only split on " / " separators.
        String[] tokens = artist.split("\\s*(?:,|;|\\s+/\\s+|&|\\+|\\band\\b|\\s+(?:feat\\.?|featuring|ft\\.?|with|x)\\s+)\\s*", -1);
        if (tokens.length == 0) {
            return artist.trim();
        }
        String primary = tokens[0].trim();
        return primary.isEmpty() ? artist.trim() : primary;
    }

    public static String normalizeTitleLevel(String title, NormLevel level) {
        if (title == null) return null;
        String t = title;
        return switch (level) {
            case RAW -> t.trim();
            case LIGHT -> canonicalizeWhitespace(stripDiacritics(t)).trim();
            case HEAVY -> canonicalizeWhitespace(
                    removeBracketedContent(removeMarketingSuffixes(stripDiacritics(t)).replace("&", "and"))
            ).trim();
        };
    }

    public static String normalizeArtistLevel(String artist, NormLevel level) {
        if (artist == null) return null;
        String a = extractPrimaryArtist(artist);
        if (a == null) return null;

        return switch (level) {
            case RAW -> a.trim();
            case LIGHT, HEAVY -> canonicalizeWhitespace(
                    stripDiacritics(a)
                            .replaceAll("(?i)\\s+(feat\\.|featuring|with|x)\\s+.*$", "")
                            .replace("&", "and")
            ).trim();
        };
    }

    public static String stripDiacritics(String s) {
        if (s == null) return null;
        String norm = Normalizer.normalize(s, Normalizer.Form.NFD);
        return norm.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    public static String canonicalizeWhitespace(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ");
    }

    private static String removeMarketingSuffixes(String t) {
        if (t == null) return null;
        return t.replaceAll("\\s*-\\s*(?i)(Remaster(ed)?|Deluxe|Expanded|Anniversary|Edition|Remix|Reissue).*$", "");
    }

    private static String removeBracketedContent(String t) {
        if (t == null) return null;
        return t.replaceAll("\\s*\\([^)]*\\)", "")
                .replaceAll("\\s*\\[[^]]*\\]", "")
                .replaceAll("\\s*\\{[^}]*\\}", "");
    }
}
