const LS_RECENTS = "vm:recentPlaylists";
const LS_CACHE_INDEX = "vm:playlistCacheIndex";
const PLAYLIST_CACHE_MAX = 5;
function cacheKeyFor(id) {
    return `vm:playlistCache:${id}`;
}
function safeParse(json, fallback) {
    if (!json)
        return fallback;
    try {
        return JSON.parse(json);
    }
    catch (_a) {
        return fallback;
    }
}
export function readRecents() {
    const raw = localStorage.getItem(LS_RECENTS);
    const list = safeParse(raw, []);
    return Array.isArray(list) ? list : [];
}
export function writeRecents(list) {
    try {
        localStorage.setItem(LS_RECENTS, JSON.stringify(list));
    }
    catch (e) {
        console.warn("Konnte Recents nicht speichern:", e);
    }
}
function readCacheIndex() {
    const raw = localStorage.getItem(LS_CACHE_INDEX);
    const list = safeParse(raw, []);
    return Array.isArray(list) ? list.filter((entry) => entry && entry.id) : [];
}
function writeCacheIndex(list) {
    try {
        localStorage.setItem(LS_CACHE_INDEX, JSON.stringify(list));
    }
    catch (e) {
        console.warn("Konnte Playlist-Index nicht speichern:", e);
    }
}
function updateCacheIndex(id) {
    if (!id)
        return;
    const now = Date.now();
    let index = readCacheIndex().filter((entry) => entry.id !== id);
    index.unshift({ id, updatedAt: now });
    if (index.length > PLAYLIST_CACHE_MAX) {
        const removed = index.splice(PLAYLIST_CACHE_MAX);
        for (const entry of removed) {
            try {
                localStorage.removeItem(cacheKeyFor(entry.id));
            }
            catch (_a) {
                /* ignore */
            }
        }
    }
    writeCacheIndex(index);
}
export function upsertRecentFromPlaylist(id, data) {
    if (!id || !data)
        return readRecents();
    const entry = {
        id,
        name: data.playlistName ?? data.name ?? "Spotify Playlist",
        coverUrl: data.playlistCoverUrl ?? data.coverUrl ?? null,
        trackCount: typeof data.totalTracks === "number"
            ? data.totalTracks
            : Array.isArray(data.tracks)
                ? data.tracks.length
                : undefined,
    };
    const recents = readRecents().filter((item) => item && item.id !== id);
    recents.unshift(entry);
    if (recents.length > 10)
        recents.length = 10;
    writeRecents(recents);
    return recents;
}
export function readCachedPlaylist(id) {
    let targetId = id;
    if (!targetId) {
        const index = readCacheIndex();
        targetId = index.length ? index[0].id : null;
    }
    if (!targetId)
        return null;
    const raw = localStorage.getItem(cacheKeyFor(targetId));
    const data = safeParse(raw, null);
    if (!data)
        return null;
    return { id: targetId, data };
}
export function writeCachedPlaylist(id, data) {
    if (!id || !data)
        return;
    try {
        localStorage.setItem(cacheKeyFor(id), JSON.stringify(data));
    }
    catch (e) {
        console.warn("Konnte Playlist nicht speichern:", e);
    }
    updateCacheIndex(id);
}
export function clearCachedPlaylist(id) {
    if (id) {
        localStorage.removeItem(cacheKeyFor(id));
        const index = readCacheIndex().filter((entry) => entry.id !== id);
        writeCacheIndex(index);
        return;
    }
    const index = readCacheIndex();
    for (const entry of index) {
        localStorage.removeItem(cacheKeyFor(entry.id));
    }
    localStorage.removeItem(LS_CACHE_INDEX);
}
export function mergePlaylistChunk(id, chunk, existing) {
    const items = Array.isArray(chunk?.tracks) ? chunk.tracks : [];
    const offset = Number(chunk?.offset) >= 0 ? Number(chunk.offset) : 0;
    const total = typeof chunk?.totalTracks === "number"
        ? chunk.totalTracks
        : existing?.totalTracks ?? (offset + items.length);
    const base = {
        id,
        playlistName: chunk?.playlistName ?? existing?.playlistName ?? "Unbekannte Playlist",
        playlistCoverUrl: chunk?.playlistCoverUrl ?? existing?.playlistCoverUrl ?? null,
        playlistUrl: chunk?.playlistUrl ?? existing?.playlistUrl ?? null,
        totalTracks: total,
        tracks: [],
        nextOffset: typeof chunk?.nextOffset === "number"
            ? chunk.nextOffset
            : Math.min(total, offset + items.length),
        hasMore: typeof chunk?.hasMore === "boolean"
            ? chunk.hasMore
            : (typeof chunk?.nextOffset === "number"
                ? chunk.nextOffset < total
                : offset + items.length < total),
    };
    const previous = existing && Array.isArray(existing.tracks) ? [...existing.tracks] : [];
    if (items.length === 0 && existing) {
        base.tracks = previous;
        base.nextOffset = existing.nextOffset ?? base.nextOffset;
        base.hasMore = existing.hasMore ?? base.hasMore;
        return base;
    }
    const target = previous;
    for (let i = 0; i < items.length; i++) {
        target[offset + i] = items[i];
    }
    // Entferne Lücken am Ende
    while (target.length && target[target.length - 1] === undefined) {
        target.pop();
    }
    base.tracks = target;
    if (!base.hasMore && base.tracks.length < base.totalTracks) {
        base.hasMore = true;
        base.nextOffset = base.tracks.length;
    }
    return base;
}
export function storePlaylistChunk(id, chunk, existing) {
    const merged = mergePlaylistChunk(id, chunk, existing);
    writeCachedPlaylist(id, merged);
    upsertRecentFromPlaylist(id, merged);
    return merged;
}
