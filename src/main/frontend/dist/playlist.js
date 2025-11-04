import { readCachedPlaylist, storePlaylistChunk } from "./storage.js";
const PLACEHOLDER_IMG = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";
let state = {
    id: null,
    pageSize: 50,
    aggregated: null,
};
function showOverlay(message = "Playlist wird geladen …") {
    const overlay = document.getElementById("global-loading");
    if (!overlay)
        return;
    overlay.classList.remove("hidden");
    overlay.classList.add("visible");
    const text = document.getElementById("global-loading-text");
    if (text)
        text.textContent = message;
}
function hideOverlay() {
    const overlay = document.getElementById("global-loading");
    if (!overlay)
        return;
    overlay.classList.remove("visible");
    overlay.classList.add("hidden");
}
function updateSubtitle(aggregated) {
    const subtitle = document.querySelector("#playlist-header .playlist-subtitle");
    if (!subtitle)
        return;
    const loaded = aggregated?.tracks?.length ?? 0;
    const total = aggregated?.totalTracks ?? loaded;
    subtitle.textContent = total && loaded < total
        ? `${loaded} von ${total} Songs geladen`
        : `${total} Songs`;
}
function renderHeader(aggregated) {
    const header = document.getElementById("playlist-header");
    if (!header)
        return;
    header.textContent = "";
    const coverImg = document.createElement("img");
    coverImg.src = aggregated?.playlistCoverUrl || PLACEHOLDER_IMG;
    coverImg.alt = "Playlist Cover";
    const headerInfo = document.createElement("div");
    headerInfo.className = "header-info";
    const titleDiv = document.createElement("div");
    titleDiv.className = "playlist-title";
    if (aggregated?.playlistUrl) {
        const link = document.createElement("a");
        link.href = aggregated.playlistUrl;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.textContent = aggregated?.playlistName || "Playlist";
        titleDiv.appendChild(link);
    }
    else {
        titleDiv.textContent = aggregated?.playlistName || "Playlist";
    }
    const subtitle = document.createElement("div");
    subtitle.className = "playlist-subtitle";
    headerInfo.appendChild(titleDiv);
    headerInfo.appendChild(subtitle);
    header.appendChild(coverImg);
    header.appendChild(headerInfo);
    updateSubtitle(aggregated);
}
function createTrackElement(track) {
    const trackDiv = document.createElement("div");
    trackDiv.className = "track";
    const img = document.createElement("img");
    img.className = "cover";
    img.src = track.coverUrl || PLACEHOLDER_IMG;
    img.alt = `Cover von ${track.album}`;
    const infoDiv = document.createElement("div");
    infoDiv.className = "info";
    const albumDiv = document.createElement("div");
    albumDiv.className = "album";
    if (track.albumUrl) {
        const a = document.createElement("a");
        a.href = track.albumUrl;
        a.target = "_blank";
        a.rel = "noopener noreferrer";
        a.textContent = track.album;
        albumDiv.appendChild(a);
    }
    else {
        albumDiv.textContent = track.album;
    }
    const songDiv = document.createElement("div");
    songDiv.className = "song";
    const titleSpan = document.createElement("span");
    titleSpan.textContent = `${track.trackName} – `;
    const artistEm = document.createElement("em");
    artistEm.textContent = track.artist;
    songDiv.appendChild(titleSpan);
    songDiv.appendChild(artistEm);
    infoDiv.appendChild(albumDiv);
    infoDiv.appendChild(songDiv);
    const actions = document.createElement("div");
    actions.className = "actions";
    const makeBtn = (active) => {
        const a = document.createElement("a");
        a.textContent = "🔍";
        a.style.width = "32px";
        a.style.height = "32px";
        a.setAttribute("aria-label", "Auf Discogs suchen");
        const setActive = (on) => {
            if (on) {
                a.title = "Auf Discogs ansehen";
                a.style.opacity = "1";
                a.style.borderColor = "rgba(31,41,55,0.4)";
                a.style.color = "#1f2937";
                a.setAttribute("aria-disabled", "false");
            }
            else {
                a.title = "Discogs-Suche";
                a.style.opacity = "0.6";
                a.style.borderColor = "rgba(31,41,55,0.2)";
                a.style.color = "#6b7280";
                a.setAttribute("aria-disabled", "true");
            }
        };
        setActive(active);
        a._setActive = setActive;
        return a;
    };
    if (track.discogsAlbumUrl) {
        const a = makeBtn(true);
        a.href = track.discogsAlbumUrl;
        a.target = "_blank";
        a.rel = "noopener noreferrer";
        actions.appendChild(a);
    }
    else {
        const a = makeBtn(false);
        a.href = "#";
        a.addEventListener("click", async (ev) => {
            ev.preventDefault();
            try {
                const res = await fetch("/api/discogs/search", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        artist: track.artist,
                        album: track.album,
                        releaseYear: track.releaseYear ?? null,
                        track: track.trackName,
                    }),
                });
                if (!res.ok)
                    throw new Error(`HTTP ${res.status}`);
                const data = await res.json();
                const url = data?.url;
                if (url) {
                    a.href = url;
                    a.target = "_blank";
                    a.rel = "noopener noreferrer";
                    if (a._setActive)
                        a._setActive(true);
                    a.click();
                }
            }
            catch (e) {
                console.warn("Discogs-Suche fehlgeschlagen:", e);
            }
        });
        actions.appendChild(a);
    }
    if (track.discogsAlbumUrl) {
        const quoted = (s) => `"${s}"`;
        const terms = [];
        const primaryArtist = (artist) => artist.split(/\s*(?:,|;|\/|&|\s+(?:feat\.?|featuring|ft\.?|x)\s+)\s*/i)[0]?.trim() || artist;
        if (track.artist)
            terms.push(quoted(primaryArtist(track.artist)));
        if (track.album)
            terms.push(quoted(track.album));
        if (track.releaseYear)
            terms.push(String(track.releaseYear));
        const strictTokens = [
            "(lp OR vinyl OR schallplatte)"
        ];
        const qBase = encodeURIComponent([...terms, ...strictTokens].join(" "));
        const vendors = [
            { label: "H", title: "HHV", href: `https://duckduckgo.com/?q=!ducky%20site:hhv.de%20${qBase}` },
            { label: "J", title: "JPC", href: `https://duckduckgo.com/?q=!ducky%20site:jpc.de%20${qBase}` },
            { label: "A", title: "Amazon", href: `https://duckduckgo.com/?q=!ducky%20site:amazon.de%20${qBase}` },
        ];
        for (const v of vendors) {
            const b = makeBtn(true);
            b.textContent = v.label;
            b.title = v.title;
            b.href = v.href;
            b.target = "_blank";
            b.rel = "noopener noreferrer";
            actions.appendChild(b);
        }
    }
    trackDiv.appendChild(img);
    trackDiv.appendChild(infoDiv);
    trackDiv.appendChild(actions);
    return trackDiv;
}
function renderTracks(aggregated, options = {}) {
    const container = document.getElementById("playlist");
    if (!container)
        return;
    const startIndex = options.startIndex ?? 0;
    if (options.reset) {
        container.textContent = "";
    }
    const tracks = aggregated?.tracks || [];
    if (!tracks.length && options.reset) {
        const empty = document.createElement("div");
        empty.className = "playlist-empty";
        empty.textContent = "Keine Songs gefunden.";
        container.appendChild(empty);
        return;
    }
    for (let i = startIndex; i < tracks.length; i++) {
        const track = tracks[i];
        if (!track)
            continue;
        container.appendChild(createTrackElement(track));
    }
}
function updateLoadMore(aggregated) {
    const wrapper = document.getElementById("load-more-wrapper");
    const btn = document.getElementById("load-more");
    if (!wrapper || !btn)
        return;
    if (aggregated?.hasMore) {
        wrapper.classList.remove("hidden");
        btn.disabled = false;
    }
    else {
        wrapper.classList.add("hidden");
    }
}
async function fetchPlaylistChunk(id, offset, limit, { reset } = { reset: false }) {
    const query = new URLSearchParams({ id, offset: String(Math.max(0, offset)), limit: String(Math.max(1, limit)) });
    const response = await fetch(`/api/playlist?${query.toString()}`, { cache: "no-cache" });
    if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
    }
    const payload = await response.json();
    const previous = state.aggregated;
    const merged = storePlaylistChunk(id, payload, previous);
    const prevLength = previous?.tracks?.length ?? 0;
    state.aggregated = merged;
    if (reset) {
        renderHeader(merged);
        renderTracks(merged, { reset: true });
    }
    else {
        renderTracks(merged, { startIndex: prevLength });
        updateSubtitle(merged);
    }
    updateLoadMore(merged);
}
async function loadPlaylist(id, pageSize = 50) {
    const header = document.getElementById("playlist-header");
    const container = document.getElementById("playlist");
    if (!header || !container) {
        console.error("Fehlende DOM-Elemente (#playlist-header oder #playlist).");
        return;
    }
    state = { id: id || null, pageSize, aggregated: null };
    const cached = readCachedPlaylist();
    if (!state.id && cached?.id) {
        state.id = cached.id;
    }
    if (!state.id) {
        container.textContent = "No playlist ID provided and no cached playlist available.";
        return;
    }
    showOverlay();
    if (cached && cached.id === state.id) {
        state.aggregated = cached.data;
        renderHeader(state.aggregated);
        renderTracks(state.aggregated, { reset: true });
        updateLoadMore(state.aggregated);
    }
    else {
        container.textContent = "Loading...";
    }
    try {
        await fetchPlaylistChunk(state.id, 0, pageSize, { reset: true });
    }
    catch (e) {
        console.error("Fehler beim Laden der Playlist:", e);
        container.textContent = `Fehler beim Laden der Playlist: ${e instanceof Error ? e.message : String(e)}`;
    }
    finally {
        hideOverlay();
    }
    const btn = document.getElementById("load-more");
    if (btn) {
        btn.addEventListener("click", async () => {
            if (!state.aggregated?.hasMore)
                return;
            const offset = typeof state.aggregated.nextOffset === "number"
                ? state.aggregated.nextOffset
                : state.aggregated.tracks?.length ?? 0;
            btn.disabled = true;
            const originalText = btn.textContent || "Weitere Titel laden";
            btn.textContent = "Lade weitere Titel …";
            try {
                await fetchPlaylistChunk(state.id, offset, state.pageSize, { reset: false });
            }
            catch (e) {
                alert("Weitere Titel konnten nicht geladen werden: " + (e instanceof Error ? e.message : String(e)));
            }
            finally {
                btn.disabled = false;
                btn.textContent = originalText;
            }
        }, { once: false });
    }
}
export { loadPlaylist };
