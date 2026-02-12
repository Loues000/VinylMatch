package Server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts and groups albums from playlist tracks.
 * Groups tracks by album (artist + album name + release year).
 */
public class AlbumExtractor {

    /**
     * Extracts unique albums from a list of tracks.
     * Tracks are grouped by artist, album name, and release year.
     *
     * @param tracks The list of tracks to process
     * @return List of unique albums with their track indices
     */
    public List<AlbumGroup> extractAlbums(List<TrackData> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return List.of();
        }

        Map<String, AlbumBuilder> albumBuilders = new LinkedHashMap<>();

        for (int i = 0; i < tracks.size(); i++) {
            TrackData track = tracks.get(i);
            if (track == null) {
                continue;
            }

            String albumKey = buildAlbumKey(track);
            AlbumBuilder builder = albumBuilders.computeIfAbsent(albumKey, k -> new AlbumBuilder());
            
            if (builder.album == null) {
                builder.album = track.getAlbum();
                builder.artist = track.getArtist();
                builder.releaseYear = track.getReleaseYear();
                builder.coverUrl = track.getCoverUrl();
                builder.spotifyAlbumUrl = track.getAlbumUrl();
                builder.barcode = track.getBarcode();
            }
            
            builder.trackIndices.add(i);
        }

        List<AlbumGroup> albums = new ArrayList<>();
        for (AlbumBuilder builder : albumBuilders.values()) {
            albums.add(builder.build());
        }

        return Collections.unmodifiableList(albums);
    }

    private String buildAlbumKey(TrackData track) {
        String artist = normalize(track.getArtist());
        String album = normalize(track.getAlbum());
        String year = track.getReleaseYear() != null ? String.valueOf(track.getReleaseYear()) : "unknown";
        return artist + "|" + album + "|" + year;
    }

    private String normalize(String input) {
        if (input == null) return "";
        return input.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    /**
     * Helper class to build AlbumGroup objects incrementally.
     */
    private static class AlbumBuilder {
        String album;
        String artist;
        Integer releaseYear;
        String coverUrl;
        String spotifyAlbumUrl;
        String barcode;
        List<Integer> trackIndices = new ArrayList<>();

        AlbumGroup build() {
            return new AlbumGroup(
                    album,
                    artist,
                    releaseYear,
                    coverUrl,
                    trackIndices.size(),
                    trackIndices,
                    spotifyAlbumUrl,
                    barcode
            );
        }
    }
}
