package com.hctamlyniv;

import Server.ApiServer;
import Server.PlaylistData;

public class Main {

    public static void main(String[] args) {
        try {
            // 1) Tokens (falls vorhanden) laden/refreshen, nicht blockierend
            SpotifyAuth.initializeOnStartup();

            // 2) API-Server starten (ohne festen Token)
            ApiServer.start();

        } catch (Exception e) {
            System.err.println("Fehler beim Starten der Anwendung: " + e.getMessage());
            e.printStackTrace();
        }
    }
}