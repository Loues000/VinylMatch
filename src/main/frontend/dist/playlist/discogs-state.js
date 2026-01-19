/**
 * Discogs State Module
 * Manages Discogs lookup queue and batch processing
 */

import { primaryArtist, normalizeForSearch } from "../common/playlist-utils.js";
import { buildTrackKey, getRegistryEntry } from "./track-registry.js";

const DISCOGS_BATCH_SIZE = 5;
const DISCOGS_BATCH_DELAY_MS = 175;

export const discogsState = {
    queue: [],
    requested: new Set(),
    completed: new Set(),
    processing: false,
};

export function resetDiscogsState() {
    discogsState.queue.length = 0;
    discogsState.requested.clear();
    discogsState.completed.clear();
    discogsState.processing = false;
}

export function safeDiscogsUrl(url) {
    try {
        const parsed = new URL(url, window.location.origin);
        const host = parsed.hostname.toLowerCase();
        if ((parsed.protocol === "https:" || parsed.protocol === "http:") && host.endsWith("discogs.com")) {
            return parsed.href;
        }
        return null;
    } catch (_a) {
        return null;
    }
}

export function safeDiscogsImage(url) {
    return safeDiscogsUrl(url);
}

export function applyDiscogsResult(result, state, scheduleLibraryRefresh) {
    if (!result) return;
    
    const key = typeof result.key === "string" ? result.key : null;
    const index = typeof result.index === "number" ? result.index : null;
    const url = typeof result.url === "string" && result.url ? safeDiscogsUrl(result.url) : null;
    
    if (key) {
        discogsState.requested.delete(key);
        discogsState.completed.add(key);
    }
    
    if (index !== null && index !== undefined && Array.isArray(state.aggregated?.tracks)) {
        const track = state.aggregated.tracks[index];
        if (track) {
            track.discogsAlbumUrl = url;
            track.discogsStatus = url ? "found" : "not-found";
        }
    }
    
    const entry = key ? getRegistryEntry(key) : undefined;
    const targetEntry = entry ?? (index !== null && index !== undefined
        ? getRegistryEntry(buildTrackKey(state.aggregated?.tracks?.[index], index))
        : undefined);
    
    if (targetEntry?.setDiscogsState) {
        targetEntry.setDiscogsState(url ? "found" : "not-found", url ?? undefined);
    }
    
    if (url) {
        scheduleLibraryRefresh(200);
    }
}

export async function processDiscogsQueue(state, scheduleLibraryRefresh) {
    if (discogsState.processing) {
        return;
    }
    discogsState.processing = true;
    
    try {
        while (discogsState.queue.length) {
            const batch = [];
            while (discogsState.queue.length && batch.length < DISCOGS_BATCH_SIZE) {
                const candidate = discogsState.queue.shift();
                if (!candidate) {
                    continue;
                }
                if (candidate.key && discogsState.completed.has(candidate.key)) {
                    continue;
                }
                batch.push(candidate);
            }
            
            if (!batch.length) {
                continue;
            }
            
            const payload = {
                tracks: batch.map((item) => ({
                    key: item.key,
                    index: item.index,
                    artist: primaryArtist(item.track?.artist) || null,
                    album: normalizeForSearch(item.track?.album) || null,
                    releaseYear: item.track?.releaseYear ?? null,
                    track: normalizeForSearch(item.track?.trackName) || null,
                    barcode: item.track?.barcode ?? null,
                })),
            };
            
            try {
                const response = await fetch("/api/discogs/batch", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(payload),
                    credentials: "include",
                });
                
                if (!response.ok) {
                    throw new Error(`HTTP ${response.status}`);
                }
                
                const data = await response.json();
                if (Array.isArray(data?.results)) {
                    for (const result of data.results) {
                        applyDiscogsResult(result, state, scheduleLibraryRefresh);
                    }
                }
            } catch (error) {
                console.warn("Discogs-Batch fehlgeschlagen:", error);
                for (const item of batch) {
                    if (item?.key) {
                        discogsState.requested.delete(item.key);
                    }
                }
            }
            
            if (discogsState.queue.length) {
                await new Promise((resolve) => setTimeout(resolve, DISCOGS_BATCH_DELAY_MS));
            }
        }
    } finally {
        discogsState.processing = false;
    }
}

export function queueDiscogsLookups(startIndex, tracks, state, scheduleLibraryRefresh) {
    if (!Array.isArray(tracks)) {
        return;
    }
    
    for (let i = 0; i < tracks.length; i++) {
        const track = tracks[i];
        if (!track) {
            continue;
        }
        
        const index = (typeof startIndex === "number" ? startIndex : 0) + i;
        const key = buildTrackKey(track, index);
        if (!key) {
            continue;
        }
        
        if (track.discogsAlbumUrl) {
            discogsState.completed.add(key);
            applyDiscogsResult({ key, index, url: track.discogsAlbumUrl }, state, scheduleLibraryRefresh);
            continue;
        }
        
        track.discogsStatus = "pending";
        if (discogsState.completed.has(key) || discogsState.requested.has(key)) {
            continue;
        }
        
        discogsState.requested.add(key);
        discogsState.queue.push({ key, index, track });
    }
    
    return processDiscogsQueue(state, scheduleLibraryRefresh);
}

export function markDiscogsResult(key, index, url, state, scheduleLibraryRefresh) {
    applyDiscogsResult({ key, index, url }, state, scheduleLibraryRefresh);
}
