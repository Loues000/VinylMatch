const LS_RECENTS = "vm:recentPlaylists";
const LS_LAST_ID = "vm:lastPlaylistId";
const LS_LAST_DATA = "vm:lastPlaylistData";
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
export function readCachedPlaylist() {
    const id = localStorage.getItem(LS_LAST_ID) || null;
    const raw = localStorage.getItem(LS_LAST_DATA);
    const data = safeParse(raw, null);
    if (!id || !data)
        return null;
    return { id, data };
}
export function writeCachedPlaylist(id, data) {
    if (!id || !data)
        return;
    try {
        localStorage.setItem(LS_LAST_ID, id);
        localStorage.setItem(LS_LAST_DATA, JSON.stringify(data));
    }
    catch (e) {
        console.warn("Konnte Playlist nicht speichern:", e);
    }
}
export function clearCachedPlaylist() {
    localStorage.removeItem(LS_LAST_ID);
    localStorage.removeItem(LS_LAST_DATA);
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
