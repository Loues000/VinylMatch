const PLACEHOLDER_YEAR = "";
function normalize(value) {
    return (value || "").toString().trim().toLowerCase();
}
function stripBracketedContent(value) {
    if (!value)
        return "";
    return value
        .replace(/\s*\([^)]*\)/g, "")
        .replace(/\s*\[[^\]]*\]/g, "")
        .replace(/\s*\{[^}]*\}/g, "")
        .trim();
}
function removeMarketingSuffix(value) {
    if (!value)
        return "";
    return value.replace(/\s*-\s*(remaster(ed)?|deluxe|expanded|anniversary|edition|remix|reissue)\b.*$/i, "").trim();
}
function primaryArtist(artist) {
    if (!artist)
        return "";
    const tokens = artist.split(/\s*(?:,|;|\/|&|\+|\band\b|\s+(?:feat\.?|featuring|ft\.?|with|x)\s+)\s*/i);
    const candidate = tokens[0]?.trim();
    return (candidate && candidate.length ? candidate : artist).trim();
}
function normalizeForSearch(value) {
    if (!value)
        return "";
    return removeMarketingSuffix(stripBracketedContent(value.replace(/&/g, "and")));
}
function buildAlbumKey(track) {
    const artist = primaryArtist(track?.artist) || track?.artist || "";
    const album = track?.album || "";
    const year = typeof track?.releaseYear === "number" ? track.releaseYear : PLACEHOLDER_YEAR;
    return `${normalize(normalizeForSearch(artist))}|${normalize(normalizeForSearch(album))}|${year ?? PLACEHOLDER_YEAR}`;
}
function detectMissingLinks(track) {
    const missing = [];
    if (!track?.discogsAlbumUrl)
        missing.push("Discogs");
    if (!track?.hhvUrl)
        missing.push("HHV");
    if (!track?.jpcUrl)
        missing.push("JPC");
    if (!track?.amazonUrl)
        missing.push("Amazon");
    return missing;
}
function buildCurationQueue(tracks, options = {}) {
    const missingOnly = options.missingOnly !== false;
    if (!Array.isArray(tracks))
        return [];
    const seen = new Set();
    const queue = [];
    for (const track of tracks) {
        if (!track?.album)
            continue;
        const key = buildAlbumKey(track);
        if (seen.has(key))
            continue;
        const missing = detectMissingLinks(track);
        if (missingOnly && !missing.length)
            continue;
        seen.add(key);
        queue.push({
            artist: primaryArtist(track.artist) || track.artist,
            album: track.album,
            releaseYear: typeof track.releaseYear === "number" ? track.releaseYear : null,
            trackName: track.trackName,
            coverUrl: track.coverUrl,
            discogsAlbumUrl: track.discogsAlbumUrl,
            missing,
        });
    }
    return queue.sort((a, b) => {
        const aWeight = a.discogsAlbumUrl ? 1 : 0;
        const bWeight = b.discogsAlbumUrl ? 1 : 0;
        return aWeight - bWeight;
    });
}
export { buildAlbumKey, buildCurationQueue, detectMissingLinks, normalizeForSearch, primaryArtist };
