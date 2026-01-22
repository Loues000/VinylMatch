package com.hctamlyniv.discogs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiscogsUrlUtilsTest {

    @Test
    void sanitizesDiscogsUrls() {
        assertNotNull(DiscogsUrlUtils.sanitizeDiscogsWebUrl("https://www.discogs.com/release/1-test"));
        assertNull(DiscogsUrlUtils.sanitizeDiscogsWebUrl("https://example.com/release/1"));
        assertNull(DiscogsUrlUtils.sanitizeDiscogsWebUrl("javascript:alert(1)"));
    }

    @Test
    void buildsWebSearchUrl() {
        String url = DiscogsUrlUtils.buildWebSearchUrl("Daft Punk", "Discovery", 2001);
        assertTrue(url.startsWith("https://www.discogs.com/search/?q="));
        assertTrue(url.contains("year=2001"));
    }
}

