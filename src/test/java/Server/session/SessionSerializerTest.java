package Server.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionSerializerTest {

    @Test
    void spotifySessionTokensAreEncryptedAndRoundTrip() {
        SpotifySession session = new SpotifySession("session-1");
        session.setAccessToken("access-token-123");
        session.setRefreshToken("refresh-token-456");
        session.setUserId("user-1");
        session.setTokenExpiresAt(123456789L);

        String json = SessionSerializer.serializeSpotifySession(session);
        assertNotNull(json);
        assertFalse(json.contains("access-token-123"));
        assertFalse(json.contains("refresh-token-456"));

        SpotifySession restored = SessionSerializer.deserializeSpotifySession(json);
        assertNotNull(restored);
        assertEquals("session-1", restored.getSessionId());
        assertEquals("access-token-123", restored.getAccessToken());
        assertEquals("refresh-token-456", restored.getRefreshToken());
        assertEquals("user-1", restored.getUserId());
        assertEquals(123456789L, restored.getTokenExpiresAt());
    }
}
