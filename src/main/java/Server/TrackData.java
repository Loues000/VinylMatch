package Server;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class TrackData {

    private final String spotifyTrackId;
    private final String trackName;
    private final String artist;
    private final String album;
    private final Integer releaseYear;
    private final String albumUrl;
    private final String discogsAlbumUrl;
    private final String barcode;
    private final String coverUrl;

    @JsonCreator
    public TrackData(@JsonProperty("spotifyTrackId") String spotifyTrackId,
                     @JsonProperty("trackName") String trackName,
                     @JsonProperty("artist") String artist,
                     @JsonProperty("album") String album,
                     @JsonProperty("releaseYear") Integer releaseYear,
                     @JsonProperty("albumUrl") String albumUrl,
                     @JsonProperty("discogsAlbumUrl") String discogsAlbumUrl,
                     @JsonProperty("barcode") String barcode,
                     @JsonProperty("coverUrl") String coverUrl) {
        this.spotifyTrackId = (spotifyTrackId != null && !spotifyTrackId.isBlank()) ? spotifyTrackId : null;
        this.trackName = (trackName != null && !trackName.isBlank()) ? trackName : "Unknown";
        this.artist = (artist != null && !artist.isBlank()) ? artist : "Unknown";
        this.album = (album != null && !album.isBlank()) ? album : "Unknown";
        this.releaseYear = releaseYear;
        this.albumUrl = albumUrl;
        this.discogsAlbumUrl = discogsAlbumUrl;
        this.barcode = barcode;
        this.coverUrl = coverUrl;
    }

    public String getTrackName() {
        return trackName;
    }

    public String getSpotifyTrackId() {
        return spotifyTrackId;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public String getAlbumUrl() {
        return albumUrl;
    }

    public Integer getReleaseYear() {
        return releaseYear;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public String getDiscogsAlbumUrl() {
        return discogsAlbumUrl;
    }

    public String getBarcode() {
        return barcode;
    }

    @Override
    public String toString() {
        return "TrackData{" +
                "spotifyTrackId='" + spotifyTrackId + '\'' +
                ", trackName='" + trackName + '\'' +
                ", artist='" + artist + '\'' +
                ", album='" + album + '\'' +
                ", releaseYear='" + releaseYear + '\'' +
                ", albumUrl='" + albumUrl + '\'' +
                ", discogsAlbumUrl='" + discogsAlbumUrl + '\'' +
                ", barcode='" + barcode + '\'' +
                ", coverUrl='" + coverUrl + '\'' +
                '}';
    }

}
