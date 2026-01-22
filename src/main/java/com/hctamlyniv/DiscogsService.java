package com.hctamlyniv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.discogs.DiscogsApiClient;
import com.hctamlyniv.discogs.DiscogsCacheStore;
import com.hctamlyniv.discogs.DiscogsNormalizer;
import com.hctamlyniv.discogs.DiscogsUrlUtils;
import com.hctamlyniv.discogs.model.CurationCandidate;
import com.hctamlyniv.discogs.model.CuratedLink;
import com.hctamlyniv.discogs.model.DiscogsProfile;
import com.hctamlyniv.discogs.model.LibraryFlags;
import com.hctamlyniv.discogs.model.WishlistResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Discogs service facade: caching + progressive matching + optional API features (profile/wishlist).
 */
public class DiscogsService {

    private static final Logger log = LoggerFactory.getLogger(DiscogsService.class);

    private final ObjectMapper mapper;
    private final DiscogsCacheStore cacheStore;

    private final String userAgent;
    private final DiscogsApiClient apiClient;

    public DiscogsService(String token, String userAgent) {
        this(token, userAgent, null);
    }

    public DiscogsService(String token, String userAgent, Path cacheDir) {
        this.userAgent = (userAgent == null || userAgent.isBlank()) ? "VinylMatch/1.0" : userAgent;

        this.mapper = new ObjectMapper();
        this.cacheStore = (cacheDir == null) ? new DiscogsCacheStore(mapper) : new DiscogsCacheStore(cacheDir, mapper);

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.apiClient = new DiscogsApiClient(http, mapper, token, this.userAgent);

        cacheStore.load();
    }

    public Optional<String> peekCachedUri(String artist, String album, Integer releaseYear, String barcode) {
        return cacheStore.peekCachedUri(artist, album, releaseYear, barcode);
    }

    public Optional<String> findAlbumUri(String artist, String album, Integer releaseYear) {
        return findAlbumUri(artist, album, releaseYear, null, null);
    }

    public Optional<String> findAlbumUri(String artist, String album, Integer releaseYear, String trackTitle) {
        return findAlbumUri(artist, album, releaseYear, trackTitle, null);
    }

    public Optional<String> findAlbumUri(String artist, String album, Integer releaseYear, String trackTitle, String barcode) {
        final String origArtist = DiscogsNormalizer.extractPrimaryArtist(artist);
        final String origAlbum = album == null ? null : album.trim();
        final String origTrack = trackTitle == null ? null : trackTitle.trim();
        final Integer year = releaseYear;
        final String cacheKey = cacheStore.buildCacheKey(origArtist, origAlbum, year);

        Optional<String> curated = cacheStore.findCuratedLink(cacheKey, barcode);
        if (curated.isPresent()) {
            return curated;
        }

        if (barcode != null && !barcode.isBlank()) {
            Optional<String> cachedByBarcode = cacheStore.peekCachedUri(null, null, null, barcode);
            if (cachedByBarcode.isPresent()) {
                return cachedByBarcode;
            }
            if (apiClient.isConfigured()) {
                try {
                    Optional<String> byCode = apiClient.searchByBarcode(barcode);
                    if (byCode.isPresent()) {
                        cacheStore.rememberResult(cacheKey, byCode.get(), barcode);
                        return byCode;
                    }
                } catch (Exception e) {
                    log.debug("Discogs barcode lookup failed: {}", e.getMessage());
                }
            }
        }

        Optional<String> cached = cacheStore.peekCachedUri(origArtist, origAlbum, year, null);
        if (cached.isPresent()) {
            return cached;
        }

        // No token: provide a safe Discogs web search URL as fallback.
        if (!apiClient.isConfigured()) {
            String fallback = DiscogsUrlUtils.buildWebSearchUrl(
                    DiscogsNormalizer.normalizeArtistLevel(origArtist, DiscogsNormalizer.NormLevel.HEAVY),
                    origAlbum,
                    year
            );
            cacheStore.rememberResult(cacheKey, fallback, barcode);
            return Optional.of(fallback);
        }

        try {
            Optional<String> result;

            String artistStrict = DiscogsNormalizer.normalizeArtistLevel(origArtist, DiscogsNormalizer.NormLevel.HEAVY);

            // Pass A: free-text q search (raw album)
            String q1 = ((artistStrict != null) ? artistStrict : "") + " " + ((origAlbum != null) ? origAlbum : "");
            result = apiClient.searchOnceQ(q1, year, artistStrict, origAlbum);
            if (result.isPresent()) {
                cacheStore.rememberResult(cacheKey, result.get(), barcode);
                return result;
            }

            // Pass B: free-text q search (lightly normalized album)
            String lightAlbum = DiscogsNormalizer.normalizeTitleLevel(origAlbum, DiscogsNormalizer.NormLevel.LIGHT);
            String q2 = ((artistStrict != null) ? artistStrict : "") + " " + ((lightAlbum != null) ? lightAlbum : "");
            result = apiClient.searchOnceQ(q2, year, artistStrict, origAlbum);
            if (result.isPresent()) {
                cacheStore.rememberResult(cacheKey, result.get(), barcode);
                return result;
            }

            // Structured fallbacks (master preferred)
            result = apiClient.searchOnce(artistStrict, origAlbum, year, origTrack, true);
            if (result.isPresent()) { cacheStore.rememberResult(cacheKey, result.get(), barcode); return result; }

            result = apiClient.searchOnce(artistStrict, origAlbum, year, origTrack, false);
            if (result.isPresent()) { cacheStore.rememberResult(cacheKey, result.get(), barcode); return result; }

            result = apiClient.searchOnce(artistStrict, origAlbum, null, origTrack, true);
            if (result.isPresent()) { cacheStore.rememberResult(cacheKey, result.get(), barcode); return result; }

            result = apiClient.searchOnce(artistStrict, origAlbum, null, origTrack, false);
            if (result.isPresent()) { cacheStore.rememberResult(cacheKey, result.get(), barcode); return result; }

            String fallback = DiscogsUrlUtils.buildWebSearchUrl(artistStrict, origAlbum, year);
            cacheStore.rememberResult(cacheKey, fallback, barcode);
            return Optional.of(fallback);
        } catch (Exception e) {
            String fallback = DiscogsUrlUtils.buildWebSearchUrl(
                    DiscogsNormalizer.normalizeArtistLevel(artist, DiscogsNormalizer.NormLevel.HEAVY),
                    album,
                    releaseYear
            );
            cacheStore.rememberResult(cacheKey, fallback, barcode);
            return Optional.of(fallback);
        }
    }

    public Optional<DiscogsProfile> fetchProfile() {
        return apiClient.fetchProfile();
    }

    public WishlistResult fetchWishlist(String username, int page, int perPage) {
        return apiClient.fetchWishlist(username, page, perPage);
    }

    public Map<Integer, LibraryFlags> lookupLibraryFlags(String username, Set<Integer> releaseIds) {
        Map<Integer, LibraryFlags> result = new HashMap<>();
        if (releaseIds == null || releaseIds.isEmpty()) {
            return result;
        }
        Set<Integer> wishlistIds = apiClient.fetchWishlistReleaseIds(username, 100);
        Set<Integer> collectionIds = apiClient.fetchCollectionReleaseIds(username, 100);
        for (Integer id : releaseIds) {
            if (id == null) continue;
            result.put(id, new LibraryFlags(wishlistIds.contains(id), collectionIds.contains(id)));
        }
        return result;
    }

    public Optional<Integer> resolveReleaseIdFromUrl(String url) {
        Optional<Integer> id = DiscogsUrlUtils.resolveReleaseIdFromUrl(url);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        String normalized = DiscogsUrlUtils.sanitizeDiscogsWebUrl(url);
        if (normalized == null) {
            return Optional.empty();
        }
        if (normalized.toLowerCase().contains("/release/")) {
            return id;
        }
        // master -> resolve to main release if possible, else return master id
        if (apiClient.isConfigured()) {
            try {
                return apiClient.fetchMainReleaseId(id.get()).or(() -> id);
            } catch (Exception ignored) {
                return id;
            }
        }
        return id;
    }

    public boolean addToWantlist(String username, int releaseId) {
        return apiClient.addToWantlist(username, releaseId);
    }

    public java.util.List<CurationCandidate> fetchCurationCandidates(String artist, String album, Integer releaseYear, String trackTitle, int limit)
            throws java.io.IOException, InterruptedException {
        return apiClient.fetchCurationCandidates(artist, album, releaseYear, trackTitle, limit);
    }

    public CuratedLink saveCuratedLink(String artist, String album, Integer releaseYear, String trackTitle, String barcode, String url, String thumb) {
        String cacheKey = cacheStore.buildCacheKey(
                DiscogsNormalizer.extractPrimaryArtist(artist),
                album == null ? null : album.trim(),
                releaseYear
        );
        return cacheStore.saveCuratedLink(cacheKey, artist, album, releaseYear, trackTitle, barcode, url, thumb);
    }
}
