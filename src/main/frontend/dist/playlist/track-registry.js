/**
 * Track Registry Module
 * Manages track element references for dynamic updates
 */

import { primaryArtist, normalizeForSearch } from "../common/playlist-utils.js";

const trackRegistry = new Map();

function normalizeKeyPart(value) {
    if (value === null || value === undefined) {
        return "";
    }
    return value.toString().trim().toLowerCase();
}

export function buildTrackKey(track, index) {
    if (!track) {
        return null;
    }
    if (track.spotifyTrackId) {
        return `id:${track.spotifyTrackId}`;
    }
    const idx = typeof index === "number" ? `idx:${index}` : "idx:-1";
    return [
        idx,
        normalizeKeyPart(primaryArtist(track.artist)),
        normalizeKeyPart(normalizeForSearch(track.album)),
        normalizeKeyPart(normalizeForSearch(track.trackName)),
        track.releaseYear ?? "",
    ].join("|");
}

export function registerTrackElement(key, data) {
    if (!key || !data) {
        return;
    }
    trackRegistry.set(key, data);
}

export function clearTrackRegistry() {
    trackRegistry.clear();
}

export function getRegistryEntry(key) {
    if (!key) {
        return undefined;
    }
    return trackRegistry.get(key);
}
