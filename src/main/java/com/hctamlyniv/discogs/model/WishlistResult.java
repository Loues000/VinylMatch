package com.hctamlyniv.discogs.model;

import java.util.List;

public record WishlistResult(List<WishlistEntry> items, int total) {}

