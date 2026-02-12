package Server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class AlbumExtractorTest {

    @Test
    void extractAlbums_emptyList_returnsEmptyList() {
        AlbumExtractor extractor = new AlbumExtractor();
        List<AlbumGroup> result = extractor.extractAlbums(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void extractAlbums_singleTrack_returnsSingleAlbum() {
        AlbumExtractor extractor = new AlbumExtractor();
        TrackData track = new TrackData(
            "track1", "Song 1", "Artist A", "Album X",
            2020, "http://album.url", null, "123456", "http://cover.url"
        );
        
        List<AlbumGroup> result = extractor.extractAlbums(List.of(track));
        
        assertEquals(1, result.size());
        assertEquals("Album X", result.get(0).getAlbum());
        assertEquals("Artist A", result.get(0).getArtist());
        assertEquals(2020, result.get(0).getReleaseYear());
        assertEquals(1, result.get(0).getTrackCount());
        assertEquals(List.of(0), result.get(0).getTrackIndices());
    }

    @Test
    void extractAlbums_multipleTracksSameAlbum_groupsTogether() {
        AlbumExtractor extractor = new AlbumExtractor();
        TrackData track1 = new TrackData(
            "track1", "Song 1", "Artist A", "Album X",
            2020, "http://album.url", null, "123456", "http://cover.url"
        );
        TrackData track2 = new TrackData(
            "track2", "Song 2", "Artist A", "Album X",
            2020, "http://album.url", null, "123456", "http://cover.url"
        );
        
        List<AlbumGroup> result = extractor.extractAlbums(List.of(track1, track2));
        
        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getTrackCount());
        assertEquals(List.of(0, 1), result.get(0).getTrackIndices());
    }

    @Test
    void extractAlbums_differentAlbums_separateGroups() {
        AlbumExtractor extractor = new AlbumExtractor();
        TrackData track1 = new TrackData(
            "track1", "Song 1", "Artist A", "Album X",
            2020, "http://album1.url", null, "123456", "http://cover1.url"
        );
        TrackData track2 = new TrackData(
            "track2", "Song 2", "Artist B", "Album Y",
            2021, "http://album2.url", null, "789012", "http://cover2.url"
        );
        
        List<AlbumGroup> result = extractor.extractAlbums(List.of(track1, track2));
        
        assertEquals(2, result.size());
    }

    @Test
    void extractAlbums_sameAlbumDifferentArtists_separateGroups() {
        AlbumExtractor extractor = new AlbumExtractor();
        TrackData track1 = new TrackData(
            "track1", "Song 1", "Artist A", "Album X",
            2020, "http://album.url", null, "123456", "http://cover.url"
        );
        TrackData track2 = new TrackData(
            "track2", "Song 2", "Artist B", "Album X",
            2020, "http://album.url", null, "123456", "http://cover.url"
        );
        
        List<AlbumGroup> result = extractor.extractAlbums(List.of(track1, track2));
        
        assertEquals(2, result.size());
    }

    @Test
    void extractAlbums_sameAlbumDifferentYears_separateGroups() {
        AlbumExtractor extractor = new AlbumExtractor();
        TrackData track1 = new TrackData(
            "track1", "Song 1", "Artist A", "Album X",
            2020, "http://album.url", null, "123456", "http://cover.url"
        );
        TrackData track2 = new TrackData(
            "track2", "Song 2", "Artist A", "Album X",
            2021, "http://album.url", null, "123456", "http://cover.url"
        );
        
        List<AlbumGroup> result = extractor.extractAlbums(List.of(track1, track2));
        
        assertEquals(2, result.size());
    }

    @Test
    void albumGroup_getAlbumKey_returnsConsistentKey() {
        AlbumGroup group = new AlbumGroup(
            "Album X", "Artist A", 2020,
            "http://cover.url", 5, List.of(0, 1, 2, 3, 4),
            "http://album.url", "123456"
        );
        
        String key = group.getAlbumKey();
        assertNotNull(key);
        assertTrue(key.contains("artist a"));
        assertTrue(key.contains("album x"));
        assertTrue(key.contains("2020"));
    }
}
