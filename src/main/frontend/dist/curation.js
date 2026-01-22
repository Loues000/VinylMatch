import { injectHeader } from "./common/header.js";
import { buildCurationQueue, normalizeForSearch, primaryArtist } from "./common/playlist-utils.js";
import { readCachedPlaylist, storePlaylistChunk } from "./storage.js";

const DEFAULT_TEMPLATE = "";
const PAGE_SIZE = 50;
const PLACEHOLDER_IMG = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";

const curationState = {
    queue: [],
    index: 0,
    candidates: [],
    loading: false,
    saving: false,
};

const pageState = {
    id: null,
    aggregated: null,
    curation: null,
    loading: false,
};

function safeDiscogsUrl(url) {
    try {
        const parsed = new URL(url, window.location.origin);
        const host = parsed.hostname.toLowerCase();
        if ((parsed.protocol === "https:" || parsed.protocol === "http:") && host.endsWith("discogs.com")) {
            return parsed.href;
        }
        return null;
    }
    catch (_a) {
        return null;
    }
}

function safeHttpUrl(url) {
    try {
        const parsed = new URL(url, window.location.origin);
        if (parsed.protocol === "https:" || parsed.protocol === "http:") {
            return parsed.href;
        }
        return null;
    }
    catch (_a) {
        return null;
    }
}

function safeDiscogsImage(url) {
    return safeDiscogsUrl(url);
}

function select(container, selector) {
    return container?.querySelector(selector);
}

async function injectTemplate(container, templateUrl) {
    if (!container || !templateUrl)
        return;
    try {
        const res = await fetch(templateUrl);
        if (!res.ok) {
            throw new Error(`HTTP ${res.status}`);
        }
        container.innerHTML = await res.text();
    }
    catch (error) {
        console.warn("Failed to load curation template", error);
        container.innerHTML = "<p class=\"muted\">Curation panel could not be loaded.</p>";
    }
}

function resetCurationUi(container, message) {
    const album = select(container, "#curation-album");
    const grid = select(container, "#curation-candidates");
    const empty = select(container, "#curation-empty");
    if (album)
        album.textContent = "";
    if (grid)
        grid.textContent = "";
    if (empty) {
        if (message)
            empty.textContent = message;
        empty.classList.remove("hidden");
    }
}

function updateCurationProgress(container) {
    const progress = select(container, "#curation-progress");
    if (!progress)
        return;
    if (!curationState.queue.length) {
        progress.textContent = "0 / 0";
        return;
    }
    progress.textContent = `${curationState.index + 1} / ${curationState.queue.length}`;
}

function renderCurationAlbum(container, item, placeholderImage) {
    const album = select(container, "#curation-album");
    const empty = select(container, "#curation-empty");
    if (!album)
        return;
    album.textContent = "";
    if (empty)
        empty.classList.add("hidden");
    if (!item) {
        resetCurationUi(container, "No playlist tracks loaded.");
        return;
    }
    const img = document.createElement("img");
    img.className = "thumb";
    img.src = item.coverUrl || placeholderImage;
    img.alt = item.album || "Album Cover";
    const meta = document.createElement("div");
    meta.className = "meta";
    const title = document.createElement("div");
    title.className = "title";
    title.textContent = item.album || "Unknown album";
    const artist = document.createElement("div");
    artist.className = "artist";
    artist.textContent = item.artist || "Unknown artist";
    const hint = document.createElement("div");
    hint.className = "hint";
    hint.textContent = item.trackName ? `from: ${item.trackName}` : "Playlist album";
    meta.appendChild(title);
    meta.appendChild(artist);
    meta.appendChild(hint);
    if (Array.isArray(item.missing) && item.missing.length) {
        const missing = document.createElement("div");
        missing.className = "missing";
        missing.textContent = `Missing: ${item.missing.join(" • ")}`;
        meta.appendChild(missing);
    }
    album.appendChild(img);
    album.appendChild(meta);
}

function createCandidateCard(container, item, candidate, onCandidateSaved) {
    const source = candidate.source || "Discogs";
    const isDiscogs = source.toLowerCase().includes("discogs");
    const safeUrl = isDiscogs ? safeDiscogsUrl(candidate.url) : safeHttpUrl(candidate.url);
    const safeThumb = isDiscogs ? safeDiscogsImage(candidate.thumb) : safeHttpUrl(candidate.thumb) || candidate.thumb;
    if (!safeUrl) {
        return null;
    }
    const card = document.createElement("div");
    card.className = "candidate-card";
    const bar = document.createElement("div");
    bar.className = "candidate-browser";
    const origin = document.createElement("span");
    origin.className = "candidate-source";
    origin.textContent = source;
    const urlLabel = document.createElement("div");
    urlLabel.className = "url";
    urlLabel.textContent = safeUrl || "no URL";
    const openLink = document.createElement("a");
    openLink.href = safeUrl || "#";
    openLink.target = "_blank";
    openLink.rel = "noopener noreferrer";
    openLink.textContent = "Open";
    bar.appendChild(origin);
    bar.appendChild(urlLabel);
    bar.appendChild(openLink);
    const preview = document.createElement("div");
    preview.className = "candidate-preview";
    if (safeThumb) {
        const img = document.createElement("img");
        img.src = safeThumb;
        img.alt = candidate.title || "Discogs Vorschau";
        preview.appendChild(img);
    }
    else {
        const placeholder = document.createElement("div");
        placeholder.className = "placeholder";
        placeholder.textContent = "Kein Vorschaubild";
        preview.appendChild(placeholder);
    }
    const meta = document.createElement("div");
    meta.className = "candidate-meta";
    const title = document.createElement("div");
    title.className = "title";
    title.textContent = candidate.title || "Untitled";
    const details = document.createElement("div");
    details.className = "details";
    const detailParts = [];
    if (candidate.artist)
        detailParts.push(candidate.artist);
    if (candidate.year)
        detailParts.push(candidate.year);
    if (candidate.country)
        detailParts.push(candidate.country);
    if (candidate.format)
        detailParts.push(candidate.format);
    details.textContent = detailParts.join(" • ") || "Discogs result";
    meta.appendChild(title);
    meta.appendChild(details);
    const actions = document.createElement("div");
    actions.className = "candidate-actions";
    const selectButton = document.createElement("button");
    selectButton.type = "button";
    selectButton.className = isDiscogs ? "btn" : "btn ghost";
    selectButton.textContent = isDiscogs ? "Save match" : "Open in browser";
    if (isDiscogs) {
        selectButton.addEventListener("click", () => selectCandidate(container, item, candidate, selectButton, onCandidateSaved));
    }
    else {
        selectButton.addEventListener("click", () => window.open(safeUrl, "_blank", "noopener"));
    }
    actions.appendChild(selectButton);
    card.appendChild(bar);
    card.appendChild(preview);
    card.appendChild(meta);
    card.appendChild(actions);
    return card;
}

function renderCurationCandidates(container, item, candidates, onCandidateSaved) {
    const grid = select(container, "#curation-candidates");
    const empty = select(container, "#curation-empty");
    if (!grid)
        return;
    grid.textContent = "";
    if (!Array.isArray(candidates) || !candidates.length) {
        if (empty) {
            empty.textContent = "No candidates found. Try the next album.";
            empty.classList.remove("hidden");
        }
        return;
    }
    if (empty)
        empty.classList.add("hidden");
    for (const candidate of candidates) {
        const card = createCandidateCard(container, item, candidate, onCandidateSaved);
        if (card) {
            grid.appendChild(card);
        }
    }
}

async function loadCurationCandidates(container, item) {
    if (!item)
        return [];
    curationState.loading = true;
    const empty = select(container, "#curation-empty");
    if (empty) {
        empty.textContent = "Loading Discogs candidates…";
        empty.classList.remove("hidden");
    }
    try {
        const res = await fetch("/api/discogs/curation/candidates", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                artist: item.artist,
                album: item.album,
                year: item.releaseYear,
                trackTitle: item.trackName,
                preferVinyl: true,
                format: "vinyl",
            }),
        });
        if (!res.ok)
            throw new Error("HTTP " + res.status);
        const payload = await res.json();
        const discogsCandidates = Array.isArray(payload?.candidates)
            ? payload.candidates.slice(0, 3)
            : [];
        const normalizedDiscogs = discogsCandidates.map((candidate, index) => ({
            ...candidate,
            source: index === 0 ? "Discogs (Vinyl)" : index === 1 ? "Discogs (LP)" : "Discogs (Alt)",
        }));
        const query = [item.artist, item.album].filter(Boolean).join(" ");
        const searchTerm = encodeURIComponent(`${query} vinyl`);
        const storeCandidates = [
            {
                source: "Discogs (Google)",
                url: `https://www.google.com/search?q=${encodeURIComponent("site:discogs.com " + query)}`,
                title: item.album || "Discogs search",
                thumb: item.coverUrl,
                artist: item.artist,
                year: item.releaseYear,
            },
            {
                source: "HHV",
                url: `https://www.google.com/search?q=${encodeURIComponent("site:hhv.de " + query + " vinyl")}`,
                title: "HHV result",
                thumb: item.coverUrl,
                artist: item.artist,
                year: item.releaseYear,
            },
            {
                source: "JPC",
                url: `https://www.google.com/search?q=${encodeURIComponent("site:jpc.de " + query + " vinyl")}`,
                title: "JPC result",
                thumb: item.coverUrl,
                artist: item.artist,
                year: item.releaseYear,
            },
            {
                source: "Amazon",
                url: `https://www.google.com/search?q=${encodeURIComponent("site:amazon.de " + searchTerm)}`,
                title: "Amazon result",
                thumb: item.coverUrl,
                artist: item.artist,
                year: item.releaseYear,
            },
        ];
        return [...normalizedDiscogs, ...storeCandidates];
    }
    catch (e) {
        console.warn("Failed to load curation candidates", e);
        resetCurationUi(container, "Failed to load candidates.");
        return [];
    }
    finally {
        curationState.loading = false;
    }
}

async function selectCandidate(container, item, candidate, button, onCandidateSaved) {
    const safeUrl = safeDiscogsUrl(candidate?.url);
    if (!safeUrl || curationState.saving)
        return;
    curationState.saving = true;
    const original = button.textContent;
    button.textContent = "Saving…";
    button.disabled = true;
    try {
        const res = await fetch("/api/discogs/curation/save", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                artist: item.artist,
                album: item.album,
                year: item.releaseYear,
                trackTitle: item.trackName,
                url: safeUrl,
                thumb: safeDiscogsImage(candidate.thumb),
            }),
        });
        if (!res.ok)
            throw new Error("HTTP " + res.status);
        button.textContent = "Saved";
        onCandidateSaved?.(item, candidate.url);
        setTimeout(() => (button.textContent = original), 1200);
    }
    catch (e) {
        alert("Could not save link: " + (e instanceof Error ? e.message : String(e)));
        button.textContent = original;
    }
    finally {
        button.disabled = false;
        curationState.saving = false;
    }
}

async function showCurationItem(container, step, placeholderImage, onCandidateSaved) {
    if (!curationState.queue.length) {
        resetCurationUi(container, "No playlist loaded.");
        updateCurationProgress(container);
        return;
    }
    curationState.index = Math.min(Math.max(0, curationState.index + step), curationState.queue.length - 1);
    const current = curationState.queue[curationState.index];
    renderCurationAlbum(container, current, placeholderImage);
    updateCurationProgress(container);
    curationState.candidates = await loadCurationCandidates(container, current);
    renderCurationCandidates(container, current, curationState.candidates, onCandidateSaved);
}

async function initCurationPanel(options = {}) {
    const container = options.container ?? document.getElementById(options.containerId ?? "curation-panel-container");
    if (!container)
        return null;
    const templatePath = options.templateUrl ?? container.dataset.template ?? DEFAULT_TEMPLATE;
    if (templatePath) {
        await injectTemplate(container, templatePath);
    }
    resetCurationUi(container, "No playlist loaded yet.");
    updateCurationProgress(container);
    const placeholderImage = options.placeholderImage ?? "";
    const buildQueue = typeof options.buildQueue === "function" ? options.buildQueue : () => [];
    const onCandidateSaved = typeof options.onCandidateSaved === "function" ? options.onCandidateSaved : () => {};
    const startBtn = select(container, "#curation-start");
    const nextBtn = select(container, "#curation-next");
    const prevBtn = select(container, "#curation-prev");
    startBtn?.addEventListener("click", () => {
        curationState.queue = buildQueue();
        curationState.index = 0;
        if (!curationState.queue.length) {
            resetCurationUi(container, "No playlist tracks found.");
            updateCurationProgress(container);
            return;
        }
        showCurationItem(container, 0, placeholderImage, onCandidateSaved);
    });
    nextBtn?.addEventListener("click", () => showCurationItem(container, 1, placeholderImage, onCandidateSaved));
    prevBtn?.addEventListener("click", () => showCurationItem(container, -1, placeholderImage, onCandidateSaved));
    return {
        refreshQueue: () => {
            curationState.queue = buildQueue();
            curationState.index = Math.min(curationState.index, Math.max(0, curationState.queue.length - 1));
            updateCurationProgress(container);
        },
    };
}

function showLoading(message = "Loading…") {
    const overlay = document.getElementById("global-loading");
    const text = document.getElementById("global-loading-text");
    overlay?.classList.remove("hidden");
    overlay?.classList.add("visible");
    if (text)
        text.textContent = message;
}

function hideLoading() {
    const overlay = document.getElementById("global-loading");
    overlay?.classList.remove("visible");
    overlay?.classList.add("hidden");
}


function applyManualDiscogsUrl(item, url) {
    const safeUrl = typeof url === "string" && url ? url : null;
    if (!Array.isArray(pageState.aggregated?.tracks) || !safeUrl)
        return;
    for (const track of pageState.aggregated.tracks) {
        if (!track)
            continue;
        const artistMatch = normalizeForSearch(primaryArtist(track.artist) || track.artist) === normalizeForSearch(item.artist);
        const albumMatch = normalizeForSearch(track.album) === normalizeForSearch(item.album);
        const yearMatch = (typeof track.releaseYear === "number" ? track.releaseYear : null) === (item.releaseYear ?? null);
        if (artistMatch && albumMatch && yearMatch) {
            track.discogsAlbumUrl = safeUrl;
        }
    }
    item.discogsAlbumUrl = safeUrl;
    pageState.curation?.refreshQueue();
}

function updateSummary() {
    const title = document.getElementById("curation-summary-title");
    const details = document.getElementById("curation-summary-details");
    if (!title || !details)
        return;
    if (!pageState.aggregated) {
        title.textContent = "Nothing loaded yet";
        details.textContent = "Paste a playlist link above to start curation.";
        return;
    }
    title.textContent = pageState.aggregated.playlistName || "Playlist";
    const trackCount = Array.isArray(pageState.aggregated.tracks) ? pageState.aggregated.tracks.length : 0;
    details.textContent = `${trackCount} tracks loaded • only incomplete albums in the queue`;
}

async function fetchPlaylist(id) {
    let offset = 0;
    let aggregated = null;
    let hasMore = true;
    while (hasMore) {
        const query = new URLSearchParams({ id, offset: String(offset), limit: String(PAGE_SIZE) });
        const res = await fetch(`/api/playlist?${query.toString()}`, { cache: "no-cache" });
        if (!res.ok)
            throw new Error(`HTTP ${res.status}`);
        const payload = await res.json();
        aggregated = storePlaylistChunk(id, payload, aggregated ?? undefined);
        hasMore = !!aggregated?.hasMore;
        offset = typeof aggregated?.nextOffset === "number" ? aggregated.nextOffset : (offset + PAGE_SIZE);
    }
    return aggregated;
}

function getPlaylistIdFromUrl(value) {
    if (!value)
        return null;
    const trimmed = value.trim();
    const idPattern = /^[A-Za-z0-9]{22}$/;
    if (idPattern.test(trimmed))
        return trimmed;
    try {
        const parsed = new URL(trimmed);
        const hostname = parsed.hostname.toLowerCase();
        const isSpotifyHost = hostname === "spotify.com" || hostname === "open.spotify.com" || hostname === "play.spotify.com" || hostname === "www.spotify.com" || hostname.endsWith(".spotify.com");
        if (!isSpotifyHost)
            return null;
        if (parsed.protocol !== "https:" && parsed.protocol !== "http:")
            return null;
        const segments = parsed.pathname.split("/").filter(Boolean);
        if (segments[0] !== "playlist" || !segments[1] || !idPattern.test(segments[1]))
            return null;
        return segments[1].split("?")[0];
    }
    catch (_a) {
        return null;
    }
}

async function loadPlaylistFromInput() {
    if (pageState.loading)
        return;
    const textarea = document.getElementById("curation-playlist-url");
    const id = textarea ? getPlaylistIdFromUrl(textarea.value.trim()) : null;
    if (!id) {
        alert("Please paste a valid Spotify playlist URL.");
        return;
    }
    console.info("Starting playlist load", { playlistId: id });
    pageState.loading = true;
    showLoading("Loading…");
    try {
        pageState.id = id;
        pageState.aggregated = await fetchPlaylist(id);
        updateSummary();
        pageState.curation?.refreshQueue();
    }
    catch (e) {
        console.error("Playlist could not be loaded", e);
        alert("Playlist could not be loaded. Please try again later.");
    }
    finally {
        hideLoading();
        pageState.loading = false;
    }
}

async function initCurationPage() {
    await injectHeader();
    updateSummary();
    pageState.curation = await initCurationPanel({
        placeholderImage: PLACEHOLDER_IMG,
        buildQueue: () => buildCurationQueue(pageState.aggregated?.tracks),
        onCandidateSaved: (item, url) => applyManualDiscogsUrl(item, url),
        containerId: "curation-panel-container",
    });
    const cached = readCachedPlaylist(null);
    if (cached?.data) {
        pageState.id = cached.id;
        pageState.aggregated = cached.data;
        updateSummary();
        pageState.curation?.refreshQueue();
    }
    const btn = document.getElementById("curation-use-link");
    btn?.addEventListener("click", () => loadPlaylistFromInput());
}

export { initCurationPanel };

window.addEventListener("DOMContentLoaded", () => {
    if (document.getElementById("curation-page")) {
        initCurationPage().catch((error) => console.warn("Curation page could not be initialized", error));
    }
});
