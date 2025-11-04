package Server;

import java.util.Collections;
import java.util.List;

public class PlaylistData {
    private final String playlistName;
    private final String playlistCoverUrl;
    private final String playlistUrl;
    private final List<TrackData> tracks;
    private final int totalTracks;
    private final int offset;
    private final int nextOffset;
    private final boolean hasMore;

    public PlaylistData(String playlistName, String playlistCoverUrl, String playlistUrl, List<TrackData> tracks) {
        this(playlistName, playlistCoverUrl, playlistUrl, tracks,
                tracks != null ? tracks.size() : 0,
                0,
                tracks != null ? tracks.size() : 0,
                false);
    }

    public PlaylistData(String playlistName, String playlistCoverUrl, String playlistUrl, List<TrackData> tracks,
                        int totalTracks, int offset, int nextOffset, boolean hasMore) {
        this.playlistName = (playlistName != null && !playlistName.isBlank())
                ? playlistName
                : "Unbekannte Playlist";
        this.playlistCoverUrl = playlistCoverUrl;
        this.playlistUrl = playlistUrl;
        this.tracks = (tracks != null) ? List.copyOf(tracks) : Collections.emptyList();
        this.totalTracks = Math.max(totalTracks, this.tracks.size());
        this.offset = Math.max(0, offset);
        this.nextOffset = Math.max(this.offset, Math.min(nextOffset, this.totalTracks));
        this.hasMore = hasMore && this.nextOffset < this.totalTracks;
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

    public int getTotalTracks() {
        return totalTracks;
    }

    public int getOffset() {
        return offset;
    }

    public int getNextOffset() {
        return nextOffset;
    }

    public boolean isHasMore() {
        return hasMore;
    }
}
