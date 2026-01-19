/**
 * Playlist Page Coordinator
 * Main entry point for the playlist page - coordinates all modules
 */

import { initCurationPanel } from "./curation.js";
import { buildCurationQueue, normalizeForSearch, primaryArtist } from "./common/playlist-utils.js";
import { readCachedPlaylist, storePlaylistChunk } from "./storage.js";
import { loadCustomVendors } from "./common/vendors.js";

// Import modules
import { buildTrackKey, clearTrackRegistry, getRegistryEntry } from "./playlist/track-registry.js";
import { getStoredViewMode, applyViewMode, initViewToggle, DEFAULT_VIEW_MODE } from "./playlist/view-mode.js";
import { 
    discogsState, 
    resetDiscogsState, 
    queueDiscogsLookups, 
    markDiscogsResult,
    safeDiscogsUrl 
} from "./playlist/discogs-state.js";
import { 
    discogsUiState,
    setupDiscogsPanel, 
    scheduleLibraryRefresh 
} from "./playlist/discogs-ui.js";
import { createTrackElement, PLACEHOLDER_IMG } from "./playlist/track-renderer.js";

// Page state
const DEFAULT_PAGE_SIZE = 20;

let state = {
    id: null,
    pageSize: DEFAULT_PAGE_SIZE,
    aggregated: null,
    viewMode: getStoredViewMode(),
};

// Loading overlay functions
function showOverlay(message = "Playlist wird geladen …") {
    const overlay = document.getElementById("global-loading");
    if (!overlay) return;
    overlay.classList.remove("hidden");
    overlay.classList.add("visible");
    const text = document.getElementById("global-loading-text");
    if (text) text.textContent = message;
}

function hideOverlay() {
    const overlay = document.getElementById("global-loading");
    if (!overlay) return;
    overlay.classList.remove("visible");
    overlay.classList.add("hidden");
}

// Header rendering
function updateSubtitle(aggregated) {
    const subtitle = document.querySelector("#playlist-header .playlist-subtitle");
    if (!subtitle) return;
    const total = aggregated?.totalTracks ?? aggregated?.tracks?.filter(Boolean).length ?? 0;
    subtitle.textContent = `${total} Songs`;
}

function renderHeader(aggregated) {
    const header = document.getElementById("playlist-header");
    if (!header) return;
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
    } else {
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

// Track rendering
function renderTracks(aggregated, options = {}) {
    const container = document.getElementById("playlist");
    if (!container) return;
    
    container.dataset.viewMode = state.viewMode || DEFAULT_VIEW_MODE;
    const startIndex = options.startIndex ?? 0;
    
    if (options.reset) {
        container.textContent = "";
        clearTrackRegistry();
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
        if (!track) continue;
        container.appendChild(createTrackElement(track, i, state));
    }
}

// Load more handling
function updateLoadMore(aggregated) {
    const wrapper = document.getElementById("load-more-wrapper");
    const btn = document.getElementById("load-more");
    if (!wrapper || !btn) return;
    
    if (aggregated?.hasMore) {
        wrapper.classList.remove("hidden");
        btn.disabled = false;
    } else {
        wrapper.classList.add("hidden");
    }
}

// Curation panel integration
function applyManualDiscogsUrl(item, url) {
    const safeUrl = safeDiscogsUrl(url);
    if (!Array.isArray(state.aggregated?.tracks) || !safeUrl) return;
    
    for (let i = 0; i < state.aggregated.tracks.length; i++) {
        const track = state.aggregated.tracks[i];
        if (!track) continue;
        
        const artistMatch = normalizeForSearch(primaryArtist(track.artist) || track.artist) === normalizeForSearch(item.artist);
        const albumMatch = normalizeForSearch(track.album) === normalizeForSearch(item.album);
        const yearMatch = (typeof track.releaseYear === "number" ? track.releaseYear : null) === (item.releaseYear ?? null);
        
        if (artistMatch && albumMatch && yearMatch) {
            track.discogsAlbumUrl = safeUrl;
            track.discogsStatus = "found";
            const key = buildTrackKey(track, i);
            if (key) discogsState.completed.add(key);
            markDiscogsResult(key, i, safeUrl, state, (delay) => scheduleLibraryRefresh(delay, state));
        }
    }
    
    item.discogsAlbumUrl = safeUrl;
    scheduleLibraryRefresh(200, state);
}

let curationSetupPromise = null;
function setupCurationPanel() {
    if (curationSetupPromise) return curationSetupPromise;
    
    curationSetupPromise = initCurationPanel({
        placeholderImage: PLACEHOLDER_IMG,
        buildQueue: () => buildCurationQueue(state.aggregated?.tracks),
        onCandidateSaved: (item, url) => applyManualDiscogsUrl(item, url),
    }).catch((error) => {
        console.warn("Curation-Panel konnte nicht initialisiert werden", error);
        return null;
    });
    
    return curationSetupPromise;
}

// API functions
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
    } else {
        renderTracks(merged, { startIndex: prevLength });
        updateSubtitle(merged);
    }
    
    updateLoadMore(merged);
    
    const loadedOffset = typeof payload?.offset === "number" ? payload.offset : offset;
    const loadedTracks = Array.isArray(payload?.tracks) ? payload.tracks : [];
    
    if (loadedTracks.length) {
        await queueDiscogsLookups(loadedOffset, loadedTracks, state, (delay) => scheduleLibraryRefresh(delay, state));
    }
}

// Main load function
async function loadPlaylist(id, pageSize = DEFAULT_PAGE_SIZE) {
    const header = document.getElementById("playlist-header");
    const container = document.getElementById("playlist");
    
    if (!header || !container) {
        console.error("Fehlende DOM-Elemente (#playlist-header oder #playlist).");
        return;
    }
    
    // Load custom vendor configuration (non-blocking)
    loadCustomVendors().catch(() => {});
    
    // Initialize view toggle with render function
    initViewToggle(state, renderTracks);
    setupDiscogsPanel(state);
    await setupCurationPanel();
    applyViewMode(state.viewMode, state, renderTracks, { rerender: false });
    
    state = {
        id: id || null,
        pageSize,
        aggregated: null,
        viewMode: state.viewMode || getStoredViewMode(),
    };
    
    resetDiscogsState();
    
    const cached = readCachedPlaylist(id || undefined);
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
        
        if (Array.isArray(state.aggregated?.tracks) && state.aggregated.tracks.length) {
            await queueDiscogsLookups(0, state.aggregated.tracks, state, (delay) => scheduleLibraryRefresh(delay, state));
        }
    } else {
        container.textContent = "Loading...";
    }
    
    try {
        await fetchPlaylistChunk(state.id, 0, pageSize, { reset: true });
    } catch (e) {
        console.error("Fehler beim Laden der Playlist:", e);
        container.textContent = `Fehler beim Laden der Playlist: ${e instanceof Error ? e.message : String(e)}`;
    } finally {
        hideOverlay();
    }
    
    // Load more button handler
    const btn = document.getElementById("load-more");
    if (btn) {
        let loadMoreInFlight = false;
        btn.addEventListener("click", async () => {
            if (!state.aggregated?.hasMore || loadMoreInFlight) return;
            loadMoreInFlight = true;
            
            const offset = typeof state.aggregated.nextOffset === "number"
                ? state.aggregated.nextOffset
                : state.aggregated.tracks?.length ?? 0;
            
            btn.disabled = true;
            const originalText = btn.textContent || "Weitere Titel laden";
            btn.textContent = "Lade weitere Titel …";
            
            try {
                await fetchPlaylistChunk(state.id, offset, state.pageSize, { reset: false });
            } catch (e) {
                alert("Weitere Titel konnten nicht geladen werden: " + (e instanceof Error ? e.message : String(e)));
            } finally {
                btn.disabled = false;
                btn.textContent = originalText;
                loadMoreInFlight = false;
            }
        }, { once: false });
    }
}

export { loadPlaylist };
