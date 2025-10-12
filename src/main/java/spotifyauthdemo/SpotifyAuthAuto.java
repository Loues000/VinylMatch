package spotifyauthdemo;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.SpotifyHttpManager;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeRequest;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeUriRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

public class SpotifyAuthAuto {

    private static final String clientId = "b8ace7364bae4c6582a2beb5437bf099";
    private static final String clientSecret = "49b44c6bca5743778c9be8c1ef5c5efe";
    private static final URI redirectUri = SpotifyHttpManager.makeUri("http://127.0.0.1:8888/callback");

    private static final SpotifyApi spotifyApi = new SpotifyApi.Builder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setRedirectUri(redirectUri)
            .build();

    public static void main(String[] args) throws IOException {
        // 1. Autorisierungs-URL erzeugen
        AuthorizationCodeUriRequest uriRequest = spotifyApi.authorizationCodeUri()
                .scope("playlist-read-private")
                .build();
        URI uri = uriRequest.execute();

        System.out.println("Öffne diesen Link im Browser:");
        System.out.println(uri.toString());

        // 2. Starte lokalen HTTP-Server
        HttpServer server = HttpServer.create(new InetSocketAddress(8888), 0);
        server.createContext("/callback", exchange -> handleCallback(exchange));
        server.start();
        System.out.println("Warte auf Weiterleitung...");
    }

    private static void handleCallback(HttpExchange exchange) throws IOException {
        // 3. Code aus der URL extrahieren
        String query = exchange.getRequestURI().getQuery();
        String code = null;

        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("code=")) {
                    code = param.substring("code=".length());
                    break;
                }
            }
        }

        String responseMessage;

        if (code != null) {
            // 4. Tausche Code gegen Access Token
            AuthorizationCodeRequest tokenRequest = spotifyApi.authorizationCode(code).build();
            try {
                AuthorizationCodeCredentials credentials = tokenRequest.execute();
                spotifyApi.setAccessToken(credentials.getAccessToken());

                responseMessage = "Erfolgreich! Du kannst das Fenster schließen.";
                System.out.println("✅ Zugriffstoken: " + credentials.getAccessToken());


            } catch (Exception e) {
                responseMessage = "Fehler beim Abrufen des Tokens: " + e.getMessage();
                e.printStackTrace();
            }
        } else {
            responseMessage = "Kein 'code' gefunden.";
        }

        // 5. Rückmeldung im Browser
        exchange.sendResponseHeaders(200, responseMessage.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseMessage.getBytes());
        }

        // Server nach einmaliger Verwendung stoppen
        exchange.getHttpContext().getServer().stop(1);
    }
}
