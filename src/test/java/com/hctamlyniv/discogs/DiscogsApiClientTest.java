package com.hctamlyniv.discogs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.discogs.model.CurationCandidate;
import com.hctamlyniv.discogs.model.DiscogsProfile;
import com.hctamlyniv.discogs.model.WishlistResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiscogsApiClientTest {

    private HttpServer server;
    private String baseUrl;
    private DiscogsApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/oauth/identity", ex -> Json.respond(ex, 200, """
                {"username":"testuser","name":"Test User"}
                """));
        server.createContext("/masters", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/masters/123")) {
                Json.respond(ex, 200, "{\"main_release\":777}");
            } else {
                Json.respond(ex, 404, "{}");
            }
        });
        server.createContext("/users/testuser/wants", ex -> {
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                String requestBody = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (requestBody.contains("release_id=409")) {
                    Json.respond(ex, 409, "{\"message\":\"Release already in wantlist.\"}");
                    return;
                }
                if (requestBody.contains("release_id=422")) {
                    Json.respond(ex, 422, "{\"message\":\"Item already exists in wants.\"}");
                    return;
                }
                if (requestBody.contains("release_id=500")) {
                    Json.respond(ex, 500, "{\"message\":\"temporary failure\"}");
                    return;
                }
                Json.respond(ex, 201, "{}");
                return;
            }
            String rawQuery = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
            if (rawQuery.contains("page=2")) {
                Json.respond(ex, 200, """
                {
                  "pagination": { "items": 2 },
                  "wants": [
                    {
                      "basic_information": {
                        "id": 456,
                        "title": "Homework",
                        "year": 1997,
                        "thumb": "https://www.discogs.com/image/2.jpg",
                        "artists": [ { "name": "Daft Punk" } ]
                      }
                    }
                  ]
                }
                """);
                return;
            }
            Json.respond(ex, 200, """
                {
                  "pagination": { "items": 1 },
                  "wants": [
                    {
                      "basic_information": {
                        "id": 123,
                        "title": "Discovery",
                        "year": 2001,
                        "thumb": "https://www.discogs.com/image/1.jpg",
                        "uri": "/release/123-discovery",
                        "artists": [ { "name": "Daft Punk" } ]
                      }
                    }
                  ]
                }
                """);
        });
        server.createContext("/users/testuser/collection/folders/0/releases", ex -> Json.respond(ex, 200, """
                { "releases": [ { "id": 321 } ] }
                """));
        server.createContext("/database/search", ex -> {
            String rawQuery = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
            if (rawQuery.contains("barcode=")) {
                Json.respond(ex, 200, "{\"results\":[{\"uri\":\"/release/999-barcode\"}]}");
                return;
            }
            if (rawQuery.contains("q=")) {
                Json.respond(ex, 200, """
                    {
                      "results": [
                        { "type": "master", "uri": "/master/555", "title": "Daft Punk - Discovery" },
                        { "type": "release", "uri": "/release/556", "title": "Other - Something" }
                      ]
                    }
                    """);
                return;
            }
            if (rawQuery.contains("type=master")) {
                Json.respond(ex, 200, "{\"results\":[{\"uri\":\"/master/111-master\"}]}");
                return;
            }
            if (rawQuery.contains("type=release")) {
                Json.respond(ex, 200, """
                    {
                      "results": [
                        {
                          "id": 777,
                          "title": "Discovery",
                          "artist": "Daft Punk",
                          "thumb": "https://www.discogs.com/image/2.jpg",
                          "year": 2001,
                          "country": "Europe",
                          "format": ["Vinyl"],
                          "uri": "/release/777"
                        }
                      ]
                    }
                    """);
                return;
            }
            Json.respond(ex, 200, "{\"results\":[]}");
        });
        server.start();
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;

        client = new DiscogsApiClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                new ObjectMapper(),
                "test-token",
                "VinylMatch/Test",
                baseUrl
        );
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchProfileReadsIdentity() {
        Optional<DiscogsProfile> profile = client.fetchProfile();
        assertTrue(profile.isPresent());
        assertEquals("testuser", profile.get().username());
    }

    @Test
    void searchByBarcodeReturnsAbsoluteUrl() throws Exception {
        Optional<String> url = client.searchByBarcode("123");
        assertEquals("https://www.discogs.com/release/999-barcode", url.orElse(null));
    }

    @Test
    void searchOnceReturnsAbsoluteUrl() throws Exception {
        Optional<String> url = client.searchOnce("Daft Punk", "Discovery", 2001, null, true);
        assertEquals("https://www.discogs.com/master/111-master", url.orElse(null));
    }

    @Test
    void searchOnceQPrefersExactArtistAlbum() throws Exception {
        Optional<String> url = client.searchOnceQ("Daft Punk Discovery", 2001, "Daft Punk", "Discovery");
        assertEquals("https://www.discogs.com/master/555", url.orElse(null));
    }

    @Test
    void fetchWishlistParsesEntries() {
        WishlistResult wishlist = client.fetchWishlist("testuser", 1, 10);
        assertEquals(1, wishlist.total());
        assertEquals(1, wishlist.items().size());
        assertEquals("Discovery", wishlist.items().get(0).title());
    }

    @Test
    void fetchWishlistBuildsFallbackUrlWhenUriMissing() {
        WishlistResult wishlist = client.fetchWishlist("testuser", 2, 10);
        assertEquals(2, wishlist.total());
        assertEquals(1, wishlist.items().size());
        assertEquals(456, wishlist.items().get(0).releaseId());
        assertEquals("https://www.discogs.com/release/456", wishlist.items().get(0).url());
    }

    @Test
    void addToWantlistUsesPost() {
        assertTrue(client.addToWantlist("testuser", 123));
    }

    @Test
    void addToWantlistTreatsConflictAsAlreadyPresent() {
        assertTrue(client.addToWantlist("testuser", 409));
    }

    @Test
    void addToWantlistTreatsDuplicateValidationErrorAsAlreadyPresent() {
        assertTrue(client.addToWantlist("testuser", 422));
    }

    @Test
    void addToWantlistReturnsFalseOnServerError() {
        assertFalse(client.addToWantlist("testuser", 500));
    }

    @Test
    void fetchWishlistAndCollectionIdsWork() {
        Set<Integer> wants = client.fetchWishlistReleaseIds("testuser", 50);
        Set<Integer> collection = client.fetchCollectionReleaseIds("testuser", 50);
        assertTrue(wants.contains(123));
        assertTrue(collection.contains(321));
    }

    @Test
    void fetchMainReleaseIdResolvesMaster() throws Exception {
        assertEquals(777, client.fetchMainReleaseId(123).orElse(null));
    }

    @Test
    void fetchCurationCandidatesReturnsList() throws Exception {
        List<CurationCandidate> candidates = client.fetchCurationCandidates("Daft Punk", "Discovery", 2001, null, 4);
        assertEquals(1, candidates.size());
        assertEquals(777, candidates.get(0).releaseId());
        assertTrue(candidates.get(0).url().contains("discogs.com"));
    }

    private static final class Json {
        private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(code, bytes.length);
            try (var os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
