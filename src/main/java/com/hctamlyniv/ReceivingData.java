package com.hctamlyniv;

import Server.PlaylistData;
import Server.TrackData;
import org.apache.hc.core5.http.ParseException;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.specification.Album;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;
import se.michaelthelin.spotify.model_objects.specification.Episode;
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.Playlist;
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack;
import se.michaelthelin.spotify.model_objects.specification.Track;

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
        return loadPlaylistData(0, -1);
    }

    public PlaylistData loadPlaylistData(int requestedOffset, int requestedLimit) {
        try {
            Playlist playlist = spotifyApi.getPlaylist(playlistId).build().execute();
            String playlistName = playlist.getName();
            String playlistCoverUrl = (playlist.getImages() != null && playlist.getImages().length > 0)
                    ? playlist.getImages()[0].getUrl()
                    : null;
            String playlistUrl = null;
            if (playlist.getExternalUrls() != null && playlist.getExternalUrls().getExternalUrls() != null) {
                playlistUrl = playlist.getExternalUrls().getExternalUrls().get("spotify");
            }

            int safeOffset = Math.max(0, requestedOffset);
            boolean paginated = requestedLimit > 0;
            int remaining = paginated ? requestedLimit : 0;
            int currentOffset = safeOffset;
            int total = 0;

            Map<String, String> albumBarcodeCache = new HashMap<>();
            List<TrackData> tracks = new ArrayList<>();

            while (true) {
                if (paginated && remaining <= 0) {
                    break;
                }
                int requestLimit = paginated ? Math.min(remaining, 100) : 100;
                if (requestLimit <= 0) {
                    break;
                }
                Paging<PlaylistTrack> page = spotifyApi
                        .getPlaylistsItems(playlistId)
                        .limit(requestLimit)
                        .offset(currentOffset)
                        .build()
                        .execute();

                PlaylistTrack[] items = page.getItems();
                total = page.getTotal();
                if (items == null || items.length == 0) {
                    break;
                }

                Set<String> albumIds = new HashSet<>();
                for (PlaylistTrack playlistTrack : items) {
                    Object item = playlistTrack.getTrack();
                    if (item instanceof Track track) {
                        if (track.getAlbum() != null && track.getAlbum().getId() != null) {
                            albumIds.add(track.getAlbum().getId());
                        }
                    }
                }

                Map<String, Album> albumDetailsMap = new HashMap<>();
                if (!albumIds.isEmpty()) {
                    List<String> albumIdList = new ArrayList<>(albumIds);
                    List<Album> allAlbums = new ArrayList<>();

                    int batchSize = 20; // Spotify-Limit für getSeveralAlbums
                    for (int i = 0; i < albumIdList.size(); i += batchSize) {
                        int end = Math.min(i + batchSize, albumIdList.size());
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
                    if (albums != null) {
                        for (Album album : albums) {
                            if (album != null && album.getId() != null) {
                                albumDetailsMap.put(album.getId(), album);
                            }
                        }
                    }
                }

                for (PlaylistTrack playlistTrack : items) {
                    Object item = playlistTrack.getTrack();
                    if (item instanceof Track track) {
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
                                && albumDetails.getExternalUrls().getExternalUrls() != null) {
                            albumUrl = albumDetails.getExternalUrls().getExternalUrls().get("spotify");
                        } else if (track.getAlbum() != null && track.getAlbum().getExternalUrls() != null
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
                            discogsUrl = discogsService.findAlbumUri(artistName, albumName, releaseYear, null, barcode).orElse(null);
                        }

                        tracks.add(new TrackData(trackName, artistName, albumName, releaseYear, albumUrl, discogsUrl, barcode, coverUrl));
                    } else if (item instanceof Episode episode) {
                        System.out.println("Überspringe Podcast-Episode: " + episode.getName());
                    } else if (item != null) {
                        System.out.println("Unbekannter Item-Typ: " + item.getClass().getName());
                    }
                }

                currentOffset += items.length;
                if (paginated) {
                    remaining -= items.length;
                    if (currentOffset >= total) {
                        break;
                    }
                } else {
                    if (page.getNext() == null) {
                        break;
                    }
                }
            }

            boolean hasMore = paginated && currentOffset < total;
            int nextOffset = paginated ? Math.min(currentOffset, total) : currentOffset;

            return new PlaylistData(playlistName, playlistCoverUrl, playlistUrl, tracks, total, safeOffset, nextOffset, hasMore);

        } catch (IOException | SpotifyWebApiException | ParseException e) {
            System.err.println("Fehler beim Laden der Playlist: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }
}
