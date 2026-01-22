package com.hctamlyniv.discogs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.discogs.model.CuratedLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiscogsCacheStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndLoadsAlbumCacheAndBarcodeCache() {
        ObjectMapper mapper = new ObjectMapper();
        DiscogsCacheStore store = new DiscogsCacheStore(tempDir, mapper);
        store.load();

        String key = store.buildCacheKey("AC/DC", "Back In Black", 1980);
        store.rememberResult(key, "https://www.discogs.com/release/1-test", "123456");

        DiscogsCacheStore reloaded = new DiscogsCacheStore(tempDir, mapper);
        reloaded.load();

        assertEquals("https://www.discogs.com/release/1-test", reloaded.peekCachedUri("AC/DC", "Back In Black", 1980, null).orElse(null));
        assertEquals("https://www.discogs.com/release/1-test", reloaded.peekCachedUri(null, null, null, "123456").orElse(null));
    }

    @Test
    void persistsAndLoadsCuratedLinks() {
        ObjectMapper mapper = new ObjectMapper();
        DiscogsCacheStore store = new DiscogsCacheStore(tempDir, mapper);
        store.load();

        CuratedLink saved = store.saveCuratedLink(
                "k",
                "Daft Punk",
                "Discovery",
                2001,
                "One More Time",
                "barcode",
                "https://www.discogs.com/master/1-test",
                "https://www.discogs.com/image/1.jpg"
        );
        assertNotNull(saved);

        DiscogsCacheStore reloaded = new DiscogsCacheStore(tempDir, mapper);
        reloaded.load();
        assertEquals(saved.url(), reloaded.findCuratedLink("k", null).orElse(null));
    }
}

