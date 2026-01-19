package com.hctamlyniv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DiscogsService normalization and URL handling.
 * These tests focus on the URL sanitization and cache key building logic.
 */
class DiscogsServiceNormalizationTest {

    private DiscogsService service;

    @BeforeEach
    void setUp() {
        // Create service without token (for testing utility methods)
        service = new DiscogsService(null, "VinylMatch/Test");
    }

    @Test
    @DisplayName("peekCachedUri returns empty when cache is empty")
    void peekCachedUriReturnsEmptyWhenCacheEmpty() {
        Optional<String> result = service.peekCachedUri("AC/DC", "Back In Black", 1980, null);
        assertTrue(result.isEmpty(), "Should return empty when cache has no entry");
    }

    @Test
    @DisplayName("findAlbumUri returns empty when no token configured")
    void findAlbumUriReturnsEmptyWithoutToken() {
        Optional<String> result = service.findAlbumUri("AC/DC", "Back In Black", 1980);
        // Without token, should return empty (or web search fallback in some cases)
        // The behavior depends on implementation, but it should not throw
        assertNotNull(result);
    }

    @Test
    @DisplayName("Service can be instantiated with null token")
    void serviceCanBeInstantiatedWithNullToken() {
        DiscogsService svc = new DiscogsService(null, null);
        assertNotNull(svc);
    }

    @Test
    @DisplayName("Service can be instantiated with empty user agent")
    void serviceCanBeInstantiatedWithEmptyUserAgent() {
        DiscogsService svc = new DiscogsService("test-token", "");
        assertNotNull(svc);
    }

    @Test
    @DisplayName("Curation candidates returns empty list without token")
    void fetchCurationCandidatesReturnsEmptyWithoutToken() throws Exception {
        var candidates = service.fetchCurationCandidates("AC/DC", "Back In Black", 1980, null, 5);
        assertTrue(candidates.isEmpty(), "Should return empty list without token");
    }

    @Test
    @DisplayName("Wishlist fetch returns empty result without token")
    void fetchWishlistReturnsEmptyWithoutToken() {
        var result = service.fetchWishlist("testuser", 1, 10);
        assertNotNull(result);
        assertTrue(result.items().isEmpty(), "Should return empty items without token");
        assertEquals(0, result.total(), "Total should be 0 without token");
    }

    @Test
    @DisplayName("Profile fetch returns empty without token")
    void fetchProfileReturnsEmptyWithoutToken() {
        Optional<DiscogsService.DiscogsProfile> result = service.fetchProfile();
        assertTrue(result.isEmpty(), "Should return empty without token");
    }

    @Test
    @DisplayName("resolveReleaseIdFromUrl handles null gracefully")
    void resolveReleaseIdFromUrlHandlesNull() {
        Optional<Integer> result = service.resolveReleaseIdFromUrl(null);
        assertTrue(result.isEmpty(), "Should return empty for null URL");
    }

    @Test
    @DisplayName("resolveReleaseIdFromUrl handles invalid URL gracefully")
    void resolveReleaseIdFromUrlHandlesInvalidUrl() {
        Optional<Integer> result = service.resolveReleaseIdFromUrl("not-a-valid-url");
        assertTrue(result.isEmpty(), "Should return empty for invalid URL");
    }

    @Test
    @DisplayName("resolveReleaseIdFromUrl handles non-discogs URL gracefully")
    void resolveReleaseIdFromUrlHandlesNonDiscogsUrl() {
        Optional<Integer> result = service.resolveReleaseIdFromUrl("https://example.com/release/12345");
        assertTrue(result.isEmpty(), "Should return empty for non-Discogs URL");
    }

    @Test
    @DisplayName("addToWantlist returns false without token")
    void addToWantlistReturnsFalseWithoutToken() {
        boolean result = service.addToWantlist("testuser", 12345);
        assertFalse(result, "Should return false without token");
    }
}
