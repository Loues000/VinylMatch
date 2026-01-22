package com.hctamlyniv.discogs.model;

public record CuratedLink(
        String cacheKey,
        String artist,
        String album,
        Integer year,
        String trackTitle,
        String barcode,
        String url,
        String thumb,
        String collectedAt,
        String source
) {}

