package com.hctamlyniv;

import Server.PlaylistData;
import Server.TrackData;
import org.apache.hc.core5.http.ParseException;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;
import se.michaelthelin.spotify.model_objects.specification.Episode;
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.Playlist;
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.model_objects.specification.Album;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ReceivingData {

    private final SpotifyApi spotifyApi;
    private final String playlistId;

    public ReceivingData(String accessToken, String playlistId) {
        this.playlistId = playlistId;
        this.spotifyApi = new SpotifyApi.Builder()
                .setAccessToken(accessToken)
                .build();
    }

    public PlaylistData loadPlaylistData() {
        try {
            // Playlist-Metadaten (Name, Cover)
            Playlist playlist = spotifyApi.getPlaylist(playlistId).build().execute();
            String playlistName = playlist.getName();
            String playlistCoverUrl = (playlist.getImages() != null && playlist.getImages().length > 0)
                    ? playlist.getImages()[0].getUrl()
                    : null;
            String playlistUrl = null;
            if (playlist.getExternalUrls() != null && playlist.getExternalUrls().getExternalUrls() != null) {
                playlistUrl = playlist.getExternalUrls().getExternalUrls().get("spotify");
            }

            // Discogs-Service vorbereiten (optional, wenn Token fehlt, wird er ignoriert)
            String discogsToken = Config.getDiscogsToken();
            String discogsUA = Config.getDiscogsUserAgent();
            DiscogsService discogs = (discogsToken != null && !discogsToken.isBlank())
                    ? new DiscogsService(discogsToken, (discogsUA == null || discogsUA.isBlank()) ? "VinylMatch/1.0" : discogsUA)
                    : null;

            // Cache für Album -> Barcode (UPC/EAN)
            Map<String, String> albumBarcodeCache = new HashMap<>();

            // Tracks mit Pagination laden
            List<TrackData> tracks = new ArrayList<>();
            final int limit = 100;
            int offset = 0;

            while (true) {
                Paging<PlaylistTrack> page = spotifyApi
                        .getPlaylistsItems(playlistId)
                        .limit(limit)
                        .offset(offset)
                        .build()
                        .execute();

                PlaylistTrack[] items = page.getItems();
                if (items == null || items.length == 0) {
                    break;
                }

                for (PlaylistTrack playlistTrack : items) {
                    Object item = playlistTrack.getTrack();
                    if (item instanceof Track track) {
                        // Alle Künstlernamen verbinden (z. B. "Artist A, Artist B")
                        String artistName = (track.getArtists() != null && track.getArtists().length > 0)
                                ? Arrays.stream(track.getArtists())
                                .map(ArtistSimplified::getName)
                                .collect(Collectors.joining(", "))
                                : "Unknown";

                        String albumName = (track.getAlbum() != null)
                                ? track.getAlbum().getName()
                                : "Unknown";
                        Integer releaseYear = null;
                        if (track.getAlbum() != null && track.getAlbum().getReleaseDate() != null) {
                            String rd = track.getAlbum().getReleaseDate();
                            if (rd.length() >= 4) {
                                try {
                                    releaseYear = Integer.parseInt(rd.substring(0, 4));
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                        String albumUrl = null;
                        if (track.getAlbum() != null && track.getAlbum().getExternalUrls() != null
                                && track.getAlbum().getExternalUrls().getExternalUrls() != null) {
                            albumUrl = track.getAlbum().getExternalUrls().getExternalUrls().get("spotify");
                        }

                        String coverUrl = (track.getAlbum() != null
                                && track.getAlbum().getImages() != null
                                && track.getAlbum().getImages().length > 0)
                                ? track.getAlbum().getImages()[0].getUrl()
                                : null;

                        String trackName = track.getName();

                        // UPC/EAN ermitteln (nur 1x pro Album)
                        String barcode = null;
                        if (track.getAlbum() != null && track.getAlbum().getId() != null) {
                            String albumId = track.getAlbum().getId();
                            barcode = albumBarcodeCache.get(albumId);
                            if (!albumBarcodeCache.containsKey(albumId)) {
                                try {
                                    Album full = spotifyApi.getAlbum(albumId).build().execute();
                                    if (full != null && full.getExternalIds() != null && full.getExternalIds().getExternalIds() != null) {
                                        Map<String, String> ids = full.getExternalIds().getExternalIds();
                                        String upc = ids.get("upc");
                                        String ean = ids.get("ean");
                                        barcode = (upc != null && !upc.isBlank()) ? upc : ((ean != null && !ean.isBlank()) ? ean : null);
                                    }
                                } catch (Exception ignore) {
                                    // Ignorieren und ohne Barcode fortfahren
                                }
                                albumBarcodeCache.put(albumId, barcode);
                            }
                        }

                        String discogsUrl = null;
                        if (discogs != null) {
                            // Priorisiere Suche per Barcode, ansonsten heuristische Pipeline
                            discogsUrl = discogs.findAlbumUri(artistName, albumName, releaseYear, null, barcode).orElse(null);
                        }

                        tracks.add(new TrackData(trackName, artistName, albumName, releaseYear, albumUrl, discogsUrl, barcode, coverUrl));
                    } else if (item instanceof Episode episode) {
                        // Optional: Episoden überspringen oder anders behandeln
                        System.out.println("Überspringe Podcast-Episode: " + episode.getName());
                    } else if (item != null) {
                        System.out.println("Unbekannter Item-Typ: " + item.getClass().getName());
                    }
                }

                offset += items.length;
                // Wenn es keine nächste Seite gibt, aufhören
                if (page.getNext() == null) {
                    break;
                }
            }

            return new PlaylistData(playlistName, playlistCoverUrl, playlistUrl, tracks);

        } catch (IOException | SpotifyWebApiException | ParseException e) {
            System.err.println("Fehler beim Laden der Playlist: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }
}
