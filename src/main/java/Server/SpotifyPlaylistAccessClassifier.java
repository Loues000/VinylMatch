package Server;

import se.michaelthelin.spotify.exceptions.detailed.ForbiddenException;
import se.michaelthelin.spotify.exceptions.detailed.NotFoundException;

import java.util.Locale;
import java.util.Optional;

/**
 * Classifies Spotify playlist access failures into user-facing API errors.
 */
public final class SpotifyPlaylistAccessClassifier {

    private SpotifyPlaylistAccessClassifier() {}

    public static Optional<ClassifiedError> classify(Exception error, boolean userAuthenticated) {
        if (error == null) {
            return Optional.empty();
        }

        Throwable cause = unwrap(error);
        String message = normalizeMessage(cause);

        if (mentionsEditorialRestriction(message)) {
            return Optional.of(new ClassifiedError(
                    403,
                    "spotify_playlist_app_restricted",
                    "Spotify blocks this playlist for this app. Spotify-owned/editorial playlists are not available through the Web API for some apps."
            ));
        }

        if (cause instanceof ForbiddenException || cause instanceof NotFoundException) {
            if (!userAuthenticated) {
                return Optional.of(new ClassifiedError(
                        401,
                        "spotify_login_required_or_restricted",
                        "Playlist unavailable without Spotify login. It may be private/collaborative, or Spotify may block Spotify-owned/editorial playlists for this app."
                ));
            }

            return Optional.of(new ClassifiedError(
                    403,
                    "spotify_playlist_access_restricted",
                    "Spotify would not expose this playlist to VinylMatch. Spotify-owned/editorial playlists are blocked for some apps, and unavailable/private playlists can fail the same way."
            ));
        }

        return Optional.empty();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String normalizeMessage(Throwable error) {
        String message = error != null ? error.getMessage() : null;
        return message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean mentionsEditorialRestriction(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("spotify-owned")
                || message.contains("editorial playlist")
                || message.contains("algorithmic")
                || message.contains("editorial");
    }

    public record ClassifiedError(int status, String code, String message) {}
}
