package Server;

import java.util.Collections; import java.util.List;

public class PlaylistData { private final String playlistName; private final String playlistCoverUrl; private final String playlistUrl; private final List<TrackData> tracks;

    public PlaylistData(String playlistName, String playlistCoverUrl, String playlistUrl, List<TrackData> tracks) {
        this.playlistName = (playlistName != null && !playlistName.isBlank())
                ? playlistName
                : "Unbekannte Playlist";
        this.playlistCoverUrl = playlistCoverUrl;
        this.playlistUrl = playlistUrl;
        this.tracks = (tracks != null) ? List.copyOf(tracks) : Collections.emptyList();
    }

    public String getPlaylistName() {
        return playlistName;
    }

    public String getPlaylistCoverUrl() {
        return playlistCoverUrl;
    }

    public String getPlaylistUrl() {
        return playlistUrl;
    }

    public List<TrackData> getTracks() {
        return tracks;
    }
}