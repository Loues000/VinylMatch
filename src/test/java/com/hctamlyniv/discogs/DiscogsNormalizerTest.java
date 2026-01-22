package com.hctamlyniv.discogs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiscogsNormalizerTest {

    @Test
    void extractsPrimaryArtist() {
        assertEquals("AC/DC", DiscogsNormalizer.extractPrimaryArtist("AC/DC feat. Someone"));
        assertEquals("A", DiscogsNormalizer.extractPrimaryArtist("A & B"));
        assertEquals("A", DiscogsNormalizer.extractPrimaryArtist("A, B"));
    }

    @Test
    void normalizesTitles() {
        assertEquals("Back In Black", DiscogsNormalizer.normalizeTitleLevel("Back In Black (Remastered)", DiscogsNormalizer.NormLevel.HEAVY));
        assertEquals("Cafe", DiscogsNormalizer.stripDiacritics("Café"));
    }
}

