package com.hctamlyniv;

import Server.ApiServer; import Server.PlaylistData;

public class Main {

    public static void main(String[] args) {
        try {
            // 1) OAuth-Login (blockiert bis Token vorliegt)
            SpotifyAuth.ensureLoggedIn();

            // 2) Access Token abholen und prüfen
            String token = SpotifyAuth.getAccessToken();
            if (token == null || token.isBlank()) {
                System.err.println("Kein Access Token erhalten. Bitte den Login erneut durchführen.");
                return;
            }

            // 3) API-Server starten
            ApiServer.start(token);

        } catch (Exception e) {
            System.err.println("Fehler beim Starten der Anwendung: " + e.getMessage());
            e.printStackTrace();
        }
    }
}