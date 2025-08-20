package Server;

public class TrackData { private final String trackName; private final String artist; private final String album; private final Integer releaseYear; private final String albumUrl; private final String discogsAlbumUrl; private final String barcode; private final String coverUrl;

    public TrackData(String trackName, String artist, String album, Integer releaseYear, String albumUrl, String discogsAlbumUrl, String barcode, String coverUrl) {
        this.trackName = (trackName != null && !trackName.isBlank()) ? trackName : "Unknown";
        this.artist = (artist != null && !artist.isBlank()) ? artist : "Unknown";
        this.album = (album != null && !album.isBlank()) ? album : "Unknown";
        this.releaseYear = releaseYear; // darf null sein
        this.albumUrl = albumUrl; // darf null sein
        this.discogsAlbumUrl = discogsAlbumUrl; // darf null sein
        this.barcode = barcode; // UPC/EAN, darf null sein
        this.coverUrl = coverUrl; // darf null sein
    }

    public String getTrackName() {
        return trackName;
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
                "trackName='" + trackName + '\'' +
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