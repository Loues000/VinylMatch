package Server;

import Server.session.DiscogsSession;
import Server.session.SpotifySession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelTest {

    @Test
    void trackDataNormalizesDefaults() {
        TrackData t = new TrackData("  ", "  ", null, "", null, null, null, null, null);
        assertNull(t.getSpotifyTrackId());
        assertEquals("Unknown", t.getTrackName());
        assertEquals("Unknown", t.getArtist());
        assertEquals("Unknown", t.getAlbum());
    }

    @Test
    void playlistDataComputesPagination() {
        TrackData track = new TrackData("id", "name", "artist", "album", 2001, null, null, null, null);
        PlaylistData data = new PlaylistData(null, null, null, List.of(track), 1, -10, 100, true);
        assertEquals("Unbekannte Playlist", data.getPlaylistName());
        assertEquals(0, data.getOffset());
        assertEquals(1, data.getNextOffset());
        assertFalse(data.isHasMore());
    }

    @Test
    void userPlaylistsResponseComputesHasMore() {
        PlaylistSummary p = new PlaylistSummary("id", "name", null, 1, "owner");
        UserPlaylistsResponse resp = new UserPlaylistsResponse(List.of(p), 10, 0, 5);
        assertEquals(1, resp.getItems().size());
        assertTrue(resp.isHasMore());
        assertEquals(1, resp.getNextOffset());
    }

    @Test
    void spotifySessionTracksLoginState() {
        SpotifySession session = new SpotifySession("s1");
        assertFalse(session.isLoggedIn());
        session.setAccessToken("token");
        assertTrue(session.isLoggedIn());
        session.setTokenExpiresAt(System.currentTimeMillis() - 1);
        assertTrue(session.isTokenExpired());
    }

    @Test
    void discogsSessionRecordHoldsValues() {
        DiscogsSession session = new DiscogsSession("sid", "t", null, "ua", "u", "n");
        assertEquals("u", session.username());
    }
}
