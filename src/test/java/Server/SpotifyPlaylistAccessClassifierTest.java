package Server;

import org.junit.jupiter.api.Test;
import se.michaelthelin.spotify.exceptions.detailed.ForbiddenException;
import se.michaelthelin.spotify.exceptions.detailed.NotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyPlaylistAccessClassifierTest {

    @Test
    void classifiesExplicitEditorialRestrictionMessage() {
        Optional<SpotifyPlaylistAccessClassifier.ClassifiedError> result =
                SpotifyPlaylistAccessClassifier.classify(
                        new ForbiddenException("Algorithmic and Spotify-owned editorial playlists are unavailable"),
                        true
                );

        assertTrue(result.isPresent());
        assertEquals("spotify_playlist_app_restricted", result.get().code());
        assertEquals(403, result.get().status());
    }

    @Test
    void classifiesAnonymousNotFoundAsLoginOrRestricted() {
        Optional<SpotifyPlaylistAccessClassifier.ClassifiedError> result =
                SpotifyPlaylistAccessClassifier.classify(new NotFoundException("Resource not found"), false);

        assertTrue(result.isPresent());
        assertEquals("spotify_login_required_or_restricted", result.get().code());
        assertEquals(401, result.get().status());
    }

    @Test
    void classifiesAuthenticatedForbiddenAsRestricted() {
        Optional<SpotifyPlaylistAccessClassifier.ClassifiedError> result =
                SpotifyPlaylistAccessClassifier.classify(new ForbiddenException("Forbidden"), true);

        assertTrue(result.isPresent());
        assertEquals("spotify_playlist_access_restricted", result.get().code());
        assertEquals(403, result.get().status());
    }

    @Test
    void ignoresUnrelatedFailures() {
        Optional<SpotifyPlaylistAccessClassifier.ClassifiedError> result =
                SpotifyPlaylistAccessClassifier.classify(new IllegalStateException("boom"), true);

        assertTrue(result.isEmpty());
    }
}
