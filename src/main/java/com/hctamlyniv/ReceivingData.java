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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ReceivingData {

    private final SpotifyApi spotifyApi;
    private final String playlistId;
    private final DiscogsService discogsService;

    public ReceivingData(String accessToken, String playlistId, DiscogsService discogsService) {
        this.playlistId = playlistId;
        this.spotifyApi = new SpotifyApi.Builder()
                .setAccessToken(accessToken)
                .build();
        this.discogsService = discogsService;
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

                Set<String> albumIds = new HashSet<>();
                for(PlaylistTrack playlistTrack: items){
                    Object item = playlistTrack.getTrack();
                    if (item instanceof Track track) {
                        if (track.getAlbum() != null && track.getAlbum().getId() != null) {
                            albumIds.add(track.getAlbum().getId());
                        }
                    }
                }

                Map<String, Album> albumDetailsMap = new HashMap<>();
                if (!albumIds.isEmpty()){
                    List<String> albumIdList = new ArrayList<>(albumIds);
                    List<Album> allAlbums = new ArrayList<>();

                    int batchSize = 20; // Spotify-Limit für getSeveralAlbums
                    for (int i = 0; i < albumIds.size(); i += batchSize) {
                        int end = Math.min(i + batchSize, albumIds.size());
                        List<String> batch = albumIdList.subList(i, end);

                        try {
                            Album[] batchAlbums = spotifyApi
                                    .getSeveralAlbums(batch.toArray(new String[0]))
                                    .build()
                                    .execute();

                            allAlbums.addAll(Arrays.asList(batchAlbums));
                        } catch (Exception e) {
                            System.err.println("[Spotify] Fehler beim Laden der Alben (Batch " + (i / batchSize + 1) + "): " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                    Album[] albums = allAlbums.toArray(new Album[0]);
                    if (albums != null){
                        for (Album album : albums){
                            if(album != null && album.getId() != null) {
                                albumDetailsMap.put(album.getId(), album);
                            }
                        }
                    }
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

                        String albumId = (track.getAlbum() != null) ? track.getAlbum().getId() : null;
                        Album albumDetails = (albumId != null) ? albumDetailsMap.get(albumId) : null;

                        String albumName = (albumDetails != null) ? albumDetails.getName()
                                : (track.getAlbum() != null ? track.getAlbum().getName() : "Unknown");
                        Integer releaseYear = null;
                        String releaseDate = null;
                        if (albumDetails != null && albumDetails.getReleaseDate() != null) {
                            releaseDate = albumDetails.getReleaseDate();
                        } else if (track.getAlbum() != null) {
                            releaseDate = track.getAlbum().getReleaseDate();
                        }
                        if (releaseDate != null && releaseDate.length() >= 4) {
                            try {
                                releaseYear = Integer.parseInt(releaseDate.substring(0, 4));
                            } catch (NumberFormatException ignore) {
                                // Ignorieren, Jahr bleibt null
                            }
                        }
                        String albumUrl = null;
                        if (albumDetails != null && albumDetails.getExternalUrls() != null
                                && albumDetails.getExternalUrls().getExternalUrls() != null){
                            albumUrl = albumDetails.getExternalUrls().getExternalUrls().get("spotify");
                        }else if(track.getAlbum() != null && track.getAlbum().getExternalUrls() != null
                                && track.getAlbum().getExternalUrls().getExternalUrls() != null) {
                            albumUrl = track.getAlbum().getExternalUrls().getExternalUrls().get("spotify");
                        }

                        String coverUrl = null;
                        if (albumDetails != null && albumDetails.getImages() != null && albumDetails.getImages().length > 0) {
                            coverUrl = albumDetails.getImages()[0].getUrl();
                        } else if (track.getAlbum() != null && track.getAlbum().getImages() != null && track.getAlbum().getImages().length > 0) {
                            coverUrl = track.getAlbum().getImages()[0].getUrl();
                        }

                        String trackName = track.getName();

                        // UPC/EAN ermitteln (nur 1x pro Album)
                        String barcode = null;
                        if (albumId != null) {
                            barcode = albumBarcodeCache.get(albumId);
                            if (!albumBarcodeCache.containsKey(albumId)) {
                                if (albumDetails != null && albumDetails.getExternalIds() != null
                                        && albumDetails.getExternalIds().getExternalIds() != null) {
                                    Map<String, String> ids = albumDetails.getExternalIds().getExternalIds();
                                    String upc = ids.get("upc");
                                    String ean = ids.get("ean");
                                    barcode = (upc != null && !upc.isBlank()) ? upc
                                            : ((ean != null && !ean.isBlank()) ? ean : null);
                                }
                                albumBarcodeCache.put(albumId, barcode);
                            }
                        }

                        String discogsUrl = null;
                        if (discogsService != null) {
                            // Priorisiere Suche per Barcode, ansonsten heuristische Pipeline
                            discogsUrl = discogsService.findAlbumUri(artistName, albumName, releaseYear, null, barcode).orElse(null);
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
