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
    
    /**
     * Serialize a SpotifySession to JSON string.
     */
    public static String serializeSpotifySession(SpotifySession session) {
        if (session == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(session);
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
            return mapper.readValue(json, SpotifySession.class);
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
}
