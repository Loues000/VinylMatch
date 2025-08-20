package com.hctamlyniv;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.SpotifyHttpManager;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeRequest;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeUriRequest;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Executors;

public class SpotifyAuth {

    // 1) Credentials (trim gegen versehentliche Leerzeichen)
    private static final String clientId = trimOrNull(Config.getSpotifyClientId());
    private static final String clientSecret = trimOrNull(Config.getSpotifyClientSecret());

    // 2) Callback / Token-Datei
    private static final int CALLBACK_PORT = 8890;
    private static final URI redirectUri = SpotifyHttpManager.makeUri("http://127.0.0.1:" + CALLBACK_PORT + "/callback");
    private static final Path TOKEN_FILE = Path.of(System.getProperty("user.home"), "VinylMatch", "spotify.tokens");

    private static final Object lock = new Object();
    private static String accessToken;
    private static String refreshToken;

    private static final SpotifyApi spotifyApi = new SpotifyApi.Builder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setRedirectUri(redirectUri)
            .build();

    // Aufruf aus Main: SpotifyAuth.ensureLoggedIn();
    public static void ensureLoggedIn() throws IOException, InterruptedException {
        if (clientId == null || clientSecret == null) {
            System.err.println("Fehlende Spotify-Credentials. Bitte SPOTIFY_CLIENT_ID/SECRET setzen.");
            throw new IllegalStateException("Missing client credentials");
        }

        // 1) Versuche gespeicherte Tokens zu laden und Access-Token zu refreshen
        if (loadTokensFromDisk()) {
            if (refreshAccessToken()) {
                System.out.println("Access-Token per Refresh-Token erneuert.");
                return;
            } else {
                System.out.println("Gespeicherte Tokens ungültig, starte interaktiven Login…");
            }
        } else {
            System.out.println("Keine gespeicherten Tokens gefunden, starte interaktiven Login…");
        }

        // 2) Interaktiver Login (blockiert bis Callback)
        loginInteractive();
    }

    // Beibehaltung der bisherigen main als „nur interaktiver Login“-Entry
    public static void main(String[] args) throws IOException, InterruptedException {
        loginInteractive();
    }

    private static void loginInteractive() throws IOException, InterruptedException {
        if (clientId == null || clientSecret == null) {
            System.err.println("Fehlende Spotify-Credentials. Bitte Umgebungsvariablen setzen:");
            System.err.println("  SPOTIFY_CLIENT_ID und SPOTIFY_CLIENT_SECRET");
            return;
        }

        AuthorizationCodeUriRequest uriRequest = spotifyApi.authorizationCodeUri()
                .scope("playlist-read-private playlist-read-collaborative")
                .build();
        URI uri = uriRequest.execute();

        System.out.println("Öffne diesen Link im Browser und melde dich an:");
        System.out.println(uri);

        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (Exception ignored) {}

        HttpServer server = HttpServer.create(new InetSocketAddress(CALLBACK_PORT), 0);
        server.createContext("/callback", exchange -> handleCallback(exchange, server));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        System.out.println("Warte auf Callback unter: " + redirectUri);

        synchronized (lock) {
            lock.wait();
        }
    }

    private static void handleCallback(HttpExchange exchange, HttpServer server) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String authCode = null;

        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("code=")) {
                    authCode = param.substring("code=".length());
                    break;
                }
            }
        }

        if (authCode == null) {
            byte[] body = "Kein 'code' in der URL. Bitte erneut versuchen.".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            return;
        }

        try {
            AuthorizationCodeRequest tokenRequest = spotifyApi.authorizationCode(authCode).build();
            AuthorizationCodeCredentials credentials = tokenRequest.execute();

            accessToken = credentials.getAccessToken();
            refreshToken = credentials.getRefreshToken();

            spotifyApi.setAccessToken(accessToken);
            spotifyApi.setRefreshToken(refreshToken);

            // Tokens persistent speichern
            saveTokensToDisk();

            String html = """
            <html><body style="font-family:sans-serif">
            <h2>Login erfolgreich</h2>
            <p>Du kannst dieses Fenster schließen und zur Anwendung zurückkehren.</p>
            </body></html>
            """;
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }

            server.stop(1);
            synchronized (lock) {
                lock.notify();
            }
        } catch (Exception e) {
            String msg = "Fehler bei der Token-Anforderung: " + e.getMessage();
            byte[] body = msg.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            e.printStackTrace();
        }
    }

    public static String getAccessToken() {
        return accessToken;
    }
    public static String getRefreshToken() {
        return refreshToken;
    }

    // Access Token per Refresh Token erneuern (+ ggf. neuen Refresh-Token übernehmen)
    public static synchronized boolean refreshAccessToken() {
        try {
            var refreshReq = spotifyApi.authorizationCodeRefresh().build();
            AuthorizationCodeCredentials creds = refreshReq.execute();
            accessToken = creds.getAccessToken();
            String newRt = creds.getRefreshToken();
            spotifyApi.setAccessToken(accessToken);
            if (newRt != null && !newRt.isBlank()) {
                refreshToken = newRt;
                spotifyApi.setRefreshToken(refreshToken);
            }
            saveTokensToDisk(); // aktualisierte Tokens speichern
            return true;
        } catch (Exception e) {
            System.err.println("Konnte Access Token nicht erneuern: " + e.getMessage());
            return false;
        }
    }

    private static boolean loadTokensFromDisk() {
        try {
            if (!Files.exists(TOKEN_FILE)) return false;
            Properties p = new Properties();
            try (var in = Files.newInputStream(TOKEN_FILE)) {
                p.load(in);
            }
            String at = trimOrNull(p.getProperty("access_token"));
            String rt = trimOrNull(p.getProperty("refresh_token"));
            if (rt != null && !rt.isBlank()) {
                accessToken = at;            // kann null/leer sein; wird gleich refreshed
                refreshToken = rt;
                spotifyApi.setAccessToken(accessToken);
                spotifyApi.setRefreshToken(refreshToken);
                System.out.println("Tokens von Disk geladen: " + TOKEN_FILE);
                return true;
            }
        } catch (Exception e) {
            System.err.println("Konnte Tokens nicht laden: " + e.getMessage());
        }
        return false;
    }

    private static void saveTokensToDisk() {
        try {
            Files.createDirectories(TOKEN_FILE.getParent());
            Properties p = new Properties();
            if (accessToken != null) p.setProperty("access_token", accessToken);
            if (refreshToken != null) p.setProperty("refresh_token", refreshToken);
            try (var out = Files.newOutputStream(TOKEN_FILE)) {
                p.store(out, "Spotify OAuth tokens");
            }
            System.out.println("Tokens gespeichert: " + TOKEN_FILE);
        } catch (Exception e) {
            System.err.println("Konnte Tokens nicht speichern: " + e.getMessage());
        }
    }

    private static String trimOrNull(String s) {
        return (s == null) ? null : s.trim();
    }

}