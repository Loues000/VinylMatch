package com.hctamlyniv.spotify;

import se.michaelthelin.spotify.model_objects.specification.Album;

import java.util.HashMap;
import java.util.Map;

/**
 * Extracts barcode information (UPC/EAN) from Spotify album data.
 */
public class BarcodeExtractor {

    private final Map<String, String> cache = new HashMap<>();

    /**
     * Extracts a barcode from album external IDs.
     * Prefers UPC, falls back to EAN.
     * 
     * @param album The album to extract barcode from
     * @return The barcode string or null if not found
     */
    public String extractBarcode(Album album) {
        if (album == null || album.getId() == null) {
            return null;
        }

        // Check cache first
        String albumId = album.getId();
        if (cache.containsKey(albumId)) {
            return cache.get(albumId);
        }

        String barcode = null;
        if (album.getExternalIds() != null && album.getExternalIds().getExternalIds() != null) {
            Map<String, String> ids = album.getExternalIds().getExternalIds();
            String upc = ids.get("upc");
            String ean = ids.get("ean");
            barcode = (upc != null && !upc.isBlank()) ? upc
                    : ((ean != null && !ean.isBlank()) ? ean : null);
        }

        // Cache the result
        cache.put(albumId, barcode);
        return barcode;
    }

    /**
     * Gets a cached barcode for an album ID, or extracts it from the album.
     */
    public String getOrExtractBarcode(String albumId, Album album) {
        if (albumId == null) {
            return null;
        }

        if (cache.containsKey(albumId)) {
            return cache.get(albumId);
        }

        return extractBarcode(album);
    }

    /**
     * Clears the barcode cache.
     */
    public void clearCache() {
        cache.clear();
    }
}
