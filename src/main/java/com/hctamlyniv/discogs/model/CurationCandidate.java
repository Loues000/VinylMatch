package com.hctamlyniv.discogs.model;

public record CurationCandidate(
        Integer releaseId,
        String title,
        String artist,
        Integer year,
        String country,
        String format,
        String thumb,
        String url
) {}

