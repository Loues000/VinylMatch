package Server.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for serializing and deserializing sessions for Redis storage.
 */
public class SessionSerializer {
    
    private static final Logger log = LoggerFactory.getLogger(SessionSerializer.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final TokenEncryption TOKEN_ENCRYPTION = new TokenEncryption();
    
    /**
     * Serialize a SpotifySession to JSON string.
     */
    public static String serializeSpotifySession(SpotifySession session) {
        if (session == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(SpotifySessionPayload.from(session, TOKEN_ENCRYPTION));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SpotifySession: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Deserialize a JSON string to SpotifySession.
     */
    public static SpotifySession deserializeSpotifySession(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            SpotifySessionPayload payload = mapper.readValue(json, SpotifySessionPayload.class);
            return payload.toSession(TOKEN_ENCRYPTION);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize SpotifySession: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Serialize a DiscogsSession to JSON string.
     */
    public static String serializeDiscogsSession(DiscogsSession session) {
        if (session == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize DiscogsSession: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Deserialize a JSON string to DiscogsSession.
     */
    public static DiscogsSession deserializeDiscogsSession(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, DiscogsSession.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize DiscogsSession: {}", e.getMessage());
            return null;
        }
    }

    private record SpotifySessionPayload(
            String sessionId,
            String accessToken,
            String refreshToken,
            String userId,
            long tokenExpiresAt,
            Boolean loggedIn
    ) {
        private static SpotifySessionPayload from(SpotifySession session, TokenEncryption encryption) {
            return new SpotifySessionPayload(
                    session.getSessionId(),
                    encryption.encrypt(session.getAccessToken()),
                    encryption.encrypt(session.getRefreshToken()),
                    session.getUserId(),
                    session.getTokenExpiresAt(),
                    session.isLoggedIn()
            );
        }

        private SpotifySession toSession(TokenEncryption encryption) {
            SpotifySession session = new SpotifySession(
                    sessionId,
                    decryptSecret(encryption, accessToken),
                    decryptSecret(encryption, refreshToken),
                    userId,
                    tokenExpiresAt
            );
            session.setLoggedIn(loggedIn);
            return session;
        }

        private static String decryptSecret(TokenEncryption encryption, String value) {
            if (value == null || value.isBlank()) {
                return value;
            }
            try {
                return encryption.decrypt(value);
            } catch (Exception e) {
                return value;
            }
        }
    }
}
