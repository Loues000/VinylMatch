package Server;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents a grouped album extracted from playlist tracks.
 * Tracks are grouped by album name, artist, and release year.
 */
public class AlbumGroup {

    private final String album;
    private final String artist;
    private final Integer releaseYear;
    private final String coverUrl;
    private final int trackCount;
    private final List<Integer> trackIndices;
    private final String spotifyAlbumUrl;
    private final String barcode;

    @JsonCreator
    public AlbumGroup(
            @JsonProperty("album") String album,
            @JsonProperty("artist") String artist,
            @JsonProperty("releaseYear") Integer releaseYear,
            @JsonProperty("coverUrl") String coverUrl,
            @JsonProperty("trackCount") int trackCount,
            @JsonProperty("trackIndices") List<Integer> trackIndices,
            @JsonProperty("spotifyAlbumUrl") String spotifyAlbumUrl,
            @JsonProperty("barcode") String barcode) {
        this.album = (album != null && !album.isBlank()) ? album : "Unknown";
        this.artist = (artist != null && !artist.isBlank()) ? artist : "Unknown";
        this.releaseYear = releaseYear;
        this.coverUrl = coverUrl;
        this.trackCount = trackCount;
        this.trackIndices = trackIndices != null ? List.copyOf(trackIndices) : List.of();
        this.spotifyAlbumUrl = spotifyAlbumUrl;
        this.barcode = barcode;
    }

    public String getAlbum() {
        return album;
    }

    public String getArtist() {
        return artist;
    }

    public Integer getReleaseYear() {
        return releaseYear;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public int getTrackCount() {
        return trackCount;
    }

    public List<Integer> getTrackIndices() {
        return trackIndices;
    }

    public String getSpotifyAlbumUrl() {
        return spotifyAlbumUrl;
    }

    public String getBarcode() {
        return barcode;
    }

    /**
     * Creates a unique key for this album based on artist, album name, and year.
     */
    public String getAlbumKey() {
        String yearStr = releaseYear != null ? String.valueOf(releaseYear) : "unknown";
        return normalizeForKey(artist) + "|" + normalizeForKey(album) + "|" + yearStr;
    }

    private String normalizeForKey(String input) {
        if (input == null) return "";
        return input.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    @Override
    public String toString() {
        return "AlbumGroup{" +
                "album='" + album + '\'' +
                ", artist='" + artist + '\'' +
                ", releaseYear=" + releaseYear +
                ", trackCount=" + trackCount +
                ", trackIndices=" + trackIndices.size() +
                '}';
    }
}
