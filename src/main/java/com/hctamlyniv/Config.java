package com.hctamlyniv;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Config {
    private static final Path CONFIG_FILE = Path.of(System.getProperty("user.home"), "VinylMatch", "spotify.properties");

    private static Properties props;

    private Config() {}

    private static synchronized void loadIfNeeded() {
        if (props != null) return;
        props = new Properties();

        // 1) Aus Datei lesen (User-Home)
        Path p = CONFIG_FILE;
        if (Files.exists(p)) {
            try (FileInputStream in = new FileInputStream(p.toFile())) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Konnte Config nicht laden: " + e.getMessage());
            }
        }

        // 2) ENV-Variablen haben Vorrang (falls gesetzt)
        String envId = System.getenv("SPOTIFY_CLIENT_ID");
        String envSecret = System.getenv("SPOTIFY_CLIENT_SECRET");
        String discogsToken = System.getenv("DISCOGS_TOKEN");
        String discogsUA = System.getenv("DISCOGS_USER_AGENT");
        if (envId != null) props.setProperty("SPOTIFY_CLIENT_ID", envId);
        if (envSecret != null) props.setProperty("SPOTIFY_CLIENT_SECRET", envSecret);
        if (discogsToken != null) props.setProperty("DISCOGS_TOKEN", discogsToken);
        if (discogsUA != null) props.setProperty("DISCOGS_USER_AGENT", discogsUA);
    }

    public static String getSpotifyClientId() {
        loadIfNeeded();
        return props.getProperty("SPOTIFY_CLIENT_ID");
    }

    public static String getSpotifyClientSecret() {
        loadIfNeeded();
        return props.getProperty("SPOTIFY_CLIENT_SECRET");
    }

    public static String getDiscogsToken() {
        loadIfNeeded();
        return props.getProperty("DISCOGS_TOKEN");
    }

    public static String getDiscogsUserAgent() {
        loadIfNeeded();
        return props.getProperty("DISCOGS_USER_AGENT");
    }


}