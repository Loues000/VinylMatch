import { readCachedPlaylist, storePlaylistChunk } from "./storage.js";
const PLACEHOLDER_IMG = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";
const VIEW_MODE_KEY = "vm:playlistViewMode";
const DEFAULT_VIEW_MODE = "list";
const DEFAULT_PAGE_SIZE = 20;
const DISCOGS_BATCH_SIZE = 5;
const DISCOGS_BATCH_DELAY_MS = 175;

const discogsState = {
    queue: [],
    requested: new Set(),
    completed: new Set(),
    processing: false,
};

const discogsUiState = {
    loggedIn: false,
    username: null,
    name: null,
    wishlist: [],
    statuses: new Map(),
};

let libraryRefreshTimer = null;

const trackRegistry = new Map();
function getStoredViewMode() {
    try {
        const stored = localStorage.getItem(VIEW_MODE_KEY);
        return stored === "grid" ? "grid" : DEFAULT_VIEW_MODE;
    }
    catch (_a) {
        return DEFAULT_VIEW_MODE;
    }
}
function storeViewMode(mode) {
    try {
        localStorage.setItem(VIEW_MODE_KEY, mode);
    }
    catch (_a) {
        /* ignore */
    }
}

function normalizeKeyPart(value) {
    if (value === null || value === undefined) {
        return "";
    }
    return value.toString().trim().toLowerCase();
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

function buildTrackKey(track, index) {
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

function registerTrackElement(key, data) {
    if (!key || !data) {
        return;
    }
    trackRegistry.set(key, data);
}

function clearTrackRegistry() {
    trackRegistry.clear();
}

function getRegistryEntry(key) {
    if (!key) {
        return undefined;
    }
    return trackRegistry.get(key);
}

function resetDiscogsState() {
    discogsState.queue.length = 0;
    discogsState.requested.clear();
    discogsState.completed.clear();
    discogsState.processing = false;
}

function scheduleLibraryRefresh(delay = 300) {
    if (libraryRefreshTimer) {
        clearTimeout(libraryRefreshTimer);
    }
    libraryRefreshTimer = window.setTimeout(() => refreshLibraryStatuses().catch(() => {}), delay);
}

function updateDiscogsChip(text, online) {
    const chip = document.getElementById("discogs-status");
    if (!chip)
        return;
    chip.textContent = text;
    chip.classList.toggle("offline", !online);
}

function renderWishlistGrid(entries, total) {
    const grid = document.getElementById("discogs-wishlist-grid");
    const empty = document.getElementById("discogs-wishlist-empty");
    const count = document.getElementById("discogs-wishlist-count");
    if (!grid || !empty || !count)
        return;
    grid.textContent = "";
    if (!entries.length) {
        empty.classList.remove("hidden");
        count.textContent = "";
        return;
    }
    empty.classList.add("hidden");
    count.textContent = total ? `${Math.min(entries.length, total)} von ${total}` : `${entries.length} Einträge`;
    for (const item of entries) {
        const card = document.createElement("article");
        card.className = "discogs-wishlist__item";
        if (item.thumb) {
            const img = document.createElement("img");
            img.src = item.thumb;
            img.alt = item.title || "Discogs Release";
            card.appendChild(img);
        }
        const title = document.createElement("h5");
        title.textContent = item.title || "Release";
        card.appendChild(title);
        const meta = document.createElement("div");
        meta.className = "discogs-wishlist__meta";
        const artist = document.createElement("span");
        artist.textContent = item.artist || "Unbekannt";
        meta.appendChild(artist);
        if (item.year) {
            const year = document.createElement("span");
            year.textContent = String(item.year);
            meta.appendChild(year);
        }
        card.appendChild(meta);
        if (item.url) {
            const link = document.createElement("a");
            link.href = item.url;
            link.target = "_blank";
            link.rel = "noopener noreferrer";
            link.textContent = "Auf Discogs öffnen";
            card.appendChild(link);
        }
        grid.appendChild(card);
    }
}

async function refreshDiscogsStatus() {
    try {
        const res = await fetch("/api/discogs/status", { cache: "no-cache" });
        if (!res.ok)
            throw new Error("HTTP " + res.status);
        const payload = await res.json();
        discogsUiState.loggedIn = !!payload?.loggedIn;
        discogsUiState.username = payload?.username || null;
        discogsUiState.name = payload?.name || null;
    }
    catch (e) {
        discogsUiState.loggedIn = false;
        discogsUiState.username = null;
    }
    finally {
        applyDiscogsUi();
    }
}

function applyDiscogsUi() {
    const login = document.getElementById("discogs-login");
    const dashboard = document.getElementById("discogs-dashboard");
    const user = document.getElementById("discogs-user");
    if (!login || !dashboard)
        return;
    if (discogsUiState.loggedIn) {
        login.classList.add("hidden");
        dashboard.classList.remove("hidden");
        updateDiscogsChip("Verbunden", true);
        if (user) {
            user.textContent = discogsUiState.name || discogsUiState.username || "Discogs";
        }
    }
    else {
        login.classList.remove("hidden");
        dashboard.classList.add("hidden");
        updateDiscogsChip("Nicht verbunden", false);
    }
}

async function refreshWishlistPreview() {
    if (!discogsUiState.loggedIn) {
        renderWishlistGrid([], 0);
        return;
    }
    try {
        const res = await fetch("/api/discogs/wishlist?limit=12", { cache: "no-cache" });
        if (!res.ok)
            throw new Error("HTTP " + res.status);
        const payload = await res.json();
        const items = Array.isArray(payload?.items) ? payload.items : [];
        discogsUiState.wishlist = items;
        renderWishlistGrid(items, payload?.total ?? items.length);
    }
    catch (e) {
        console.warn("Wunschliste konnte nicht geladen werden", e);
    }
}

async function connectDiscogs() {
    const input = document.getElementById("discogs-token");
    if (!(input instanceof HTMLInputElement))
        return;
    const token = input.value.trim();
    if (!token)
        return;
    try {
        const res = await fetch("/api/discogs/login", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ token }),
        });
        if (!res.ok)
            throw new Error("HTTP " + res.status);
        await refreshDiscogsStatus();
        await refreshWishlistPreview();
        scheduleLibraryRefresh(200);
    }
    catch (e) {
        alert("Discogs-Login fehlgeschlagen: " + (e instanceof Error ? e.message : String(e)));
    }
}

async function disconnectDiscogs() {
    try {
        await fetch("/api/discogs/logout", { method: "POST" });
    }
    catch (_) { }
    discogsUiState.loggedIn = false;
    discogsUiState.username = null;
    discogsUiState.name = null;
    discogsUiState.wishlist = [];
    applyDiscogsUi();
    renderWishlistGrid([], 0);
    refreshLibraryBadgesLocally(null);
}

async function refreshLibraryStatuses() {
    if (!discogsUiState.loggedIn || !Array.isArray(state.aggregated?.tracks)) {
        return;
    }
    const urls = state.aggregated.tracks
        .map((track) => track?.discogsAlbumUrl)
        .filter((url) => typeof url === "string" && url);
    if (!urls.length) {
        refreshLibraryBadgesLocally(null);
        return;
    }
    const uniqueUrls = Array.from(new Set(urls)).slice(0, 120);
    try {
        const res = await fetch("/api/discogs/library-status", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ urls: uniqueUrls }),
        });
        if (!res.ok)
            throw new Error("HTTP " + res.status);
        const payload = await res.json();
        const results = Array.isArray(payload?.results) ? payload.results : [];
        const flagMap = new Map();
        for (const item of results) {
            if (!item || typeof item.url !== "string")
                continue;
            flagMap.set(item.url, {
                inWishlist: !!item.inWishlist,
                inCollection: !!item.inCollection,
            });
        }
        refreshLibraryBadgesLocally(flagMap);
    }
    catch (e) {
        console.warn("Konnte Discogs-Status nicht abrufen", e);
    }
}

function refreshLibraryBadgesLocally(flagMap) {
    if (!Array.isArray(state.aggregated?.tracks)) {
        return;
    }
    for (let i = 0; i < state.aggregated.tracks.length; i++) {
        const track = state.aggregated.tracks[i];
        if (!track || !track.discogsAlbumUrl)
            continue;
        const key = buildTrackKey(track, i);
        const entry = getRegistryEntry(key);
        if (!entry?.setLibraryState)
            continue;
        const flags = flagMap?.get(track.discogsAlbumUrl);
        if (flags?.inCollection) {
            entry.setLibraryState("owned");
        }
        else if (flags?.inWishlist) {
            entry.setLibraryState("wishlist");
        }
        else {
            entry.setLibraryState(null);
        }
    }
}

function setupDiscogsPanel() {
    const connectBtn = document.getElementById("discogs-connect");
    const refreshBtn = document.getElementById("discogs-refresh");
    const disconnectBtn = document.getElementById("discogs-disconnect");
    connectBtn?.addEventListener("click", () => connectDiscogs());
    refreshBtn?.addEventListener("click", () => {
        refreshWishlistPreview();
        scheduleLibraryRefresh(100);
    });
    disconnectBtn?.addEventListener("click", () => disconnectDiscogs());
    refreshDiscogsStatus().then(() => refreshWishlistPreview()).then(() => scheduleLibraryRefresh(200));
}

function applyDiscogsResult(result) {
    if (!result)
        return;
    const key = typeof result.key === "string" ? result.key : null;
    const index = typeof result.index === "number" ? result.index : null;
    const url = typeof result.url === "string" && result.url ? result.url : null;
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

async function processDiscogsQueue() {
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
                });
                if (!response.ok) {
                    throw new Error(`HTTP ${response.status}`);
                }
                const data = await response.json();
                if (Array.isArray(data?.results)) {
                    for (const result of data.results) {
                        applyDiscogsResult(result);
                    }
                }
            }
            catch (error) {
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
    }
    finally {
        discogsState.processing = false;
    }
}

function queueDiscogsLookups(startIndex, tracks) {
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
            applyDiscogsResult({ key, index, url: track.discogsAlbumUrl });
            continue;
        }
        track.discogsStatus = "pending";
        if (discogsState.completed.has(key) || discogsState.requested.has(key)) {
            continue;
        }
        discogsState.requested.add(key);
        discogsState.queue.push({ key, index, track });
    }
    return processDiscogsQueue();
}

function markDiscogsResult(key, index, url) {
    applyDiscogsResult({ key, index, url });
}
let state = {
    id: null,
    pageSize: DEFAULT_PAGE_SIZE,
    aggregated: null,
    viewMode: getStoredViewMode(),
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
    const total = aggregated?.totalTracks ?? aggregated?.tracks?.filter(Boolean).length ?? 0;
    subtitle.textContent = `${total} Songs`;
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
function determineMatchQuality(url) {
    if (!url || typeof url !== "string") {
        return { level: "poor", label: "Kein Treffer" };
    }
    const normalized = url.toLowerCase();
    if (normalized.includes("/release/") || normalized.includes("/master/")) {
        return { level: "good", label: "Direkter Treffer" };
    }
    if (normalized.includes("/search")) {
        return { level: "medium", label: "Discogs-Suche" };
    }
    return { level: "medium", label: "Gefunden" };
}
function createQualityBadge(quality) {
    const badge = document.createElement("span");
    badge.className = `match-quality match-quality--${quality.level}`;
    badge.textContent = quality.label;
    return badge;
}
function buildVendorLinks(track) {
    const artist = primaryArtist(track.artist);
    const album = normalizeForSearch(track.album);
    const queryParts = [artist, album].filter(Boolean);
    if (track.releaseYear) {
        queryParts.push(String(track.releaseYear));
    }
    const query = queryParts.join(" ").trim();
    const encoded = query ? encodeURIComponent(query) : null;
    const vendors = [
        { label: "H", title: "HHV", href: encoded ? `https://www.hhv.de/shop/en/search?q=${encoded}` : null },
        { label: "J", title: "JPC", href: encoded ? `https://www.jpc.de/s/${encoded}` : null },
        { label: "A", title: "Amazon", href: encoded ? `https://www.amazon.de/s?k=${encoded}+vinyl` : null },
    ];
    return vendors.map((v) => {
        const b = document.createElement("a");
        b.textContent = v.label;
        b.title = v.title;
        b.setAttribute("aria-label", v.title);
        b.classList.add("vendor-link");
        if (v.href) {
            b.href = v.href;
            b.target = "_blank";
            b.rel = "noopener noreferrer";
            b.classList.remove("inactive");
            b.setAttribute("aria-disabled", "false");
        }
        else {
            b.href = "#";
            b.classList.add("inactive");
            b.setAttribute("aria-disabled", "true");
        }
        return b;
    });
}

function createTrackElement(track, index) {
    const key = buildTrackKey(track, index);
    const initialState = track.discogsAlbumUrl
        ? "found"
        : (track.discogsStatus === "not-found" ? "not-found" : "pending");
    const initialQuality = track.discogsAlbumUrl
        ? determineMatchQuality(track.discogsAlbumUrl)
        : (initialState === "not-found"
            ? { level: "poor", label: "Kein Treffer" }
            : { level: "pending", label: "Wird gesucht…" });
    const trackDiv = document.createElement("div");
    trackDiv.className = "track";
    if (key)
        trackDiv.dataset.trackKey = key;
    trackDiv.dataset.matchQuality = initialQuality.level;
    const img = document.createElement("img");
    img.className = "cover";
    img.src = track.coverUrl || PLACEHOLDER_IMG;
    img.alt = `Cover von ${track.album}`;
    const infoDiv = document.createElement("div");
    infoDiv.className = "info";
    const titleDiv = document.createElement("div");
    titleDiv.className = "track-title";
    titleDiv.textContent = track.trackName || "Unbekannter Titel";
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
    const artistsDiv = document.createElement("div");
    artistsDiv.className = "artists";
    artistsDiv.textContent = track.artist;
    const metaRow = document.createElement("div");
    metaRow.className = "meta-row";
    const qualityBadge = createQualityBadge(initialQuality);
    metaRow.appendChild(qualityBadge);
    const libraryBadge = document.createElement("span");
    libraryBadge.className = "discogs-library hidden";
    metaRow.appendChild(libraryBadge);
    if (typeof track.releaseYear === "number") {
        const year = document.createElement("span");
        year.className = "release-year";
        year.textContent = String(track.releaseYear);
        metaRow.appendChild(year);
    }
    infoDiv.appendChild(titleDiv);
    infoDiv.appendChild(albumDiv);
    infoDiv.appendChild(artistsDiv);
    infoDiv.appendChild(metaRow);
    const actions = document.createElement("div");
    actions.className = "actions";
    const discogsBtn = document.createElement("a");
    discogsBtn.textContent = "🔍";
    discogsBtn.setAttribute("aria-label", "Auf Discogs suchen");
    discogsBtn.href = "#";
    discogsBtn.classList.add("discogs-action");
    actions.appendChild(discogsBtn);
    const wishlistBtn = document.createElement("a");
    wishlistBtn.textContent = "❤";
    wishlistBtn.setAttribute("aria-label", "Zur Discogs-Wunschliste hinzufügen");
    wishlistBtn.href = "#";
    wishlistBtn.classList.add("discogs-action");
    actions.appendChild(wishlistBtn);
    const vendorLinks = buildVendorLinks(track);
    for (const link of vendorLinks) {
        if (link.getAttribute("aria-disabled") === "true") {
            link.addEventListener("click", (ev) => {
                ev.preventDefault();
                ev.stopPropagation();
            });
        }
        actions.appendChild(link);
    }
    const updateBadge = (quality) => {
        qualityBadge.textContent = quality.label;
        qualityBadge.className = `match-quality match-quality--${quality.level}`;
        trackDiv.dataset.matchQuality = quality.level;
    };
    const setDiscogsState = (state, url) => {
        trackDiv.dataset.discogsState = state;
        if (state === "found" && url) {
            const quality = determineMatchQuality(url);
            updateBadge(quality);
            track.discogsAlbumUrl = url;
            track.discogsStatus = "found";
            discogsBtn.classList.remove("inactive", "pending");
            discogsBtn.setAttribute("aria-disabled", "false");
            discogsBtn.href = url;
            discogsBtn.target = "_blank";
            discogsBtn.rel = "noopener noreferrer";
            discogsBtn.title = quality.level === "good" ? "Auf Discogs ansehen" : "Discogs-Suchergebnis";
        }
        else if (state === "pending") {
            updateBadge({ level: "pending", label: "Wird gesucht…" });
            track.discogsStatus = "pending";
            discogsBtn.href = "#";
            discogsBtn.classList.add("inactive", "pending");
            discogsBtn.setAttribute("aria-disabled", "true");
            discogsBtn.removeAttribute("target");
            discogsBtn.removeAttribute("rel");
            discogsBtn.title = "Discogs-Suche läuft …";
        }
        else {
            updateBadge({ level: "poor", label: "Kein Treffer" });
            track.discogsAlbumUrl = null;
            track.discogsStatus = "not-found";
            discogsBtn.href = "#";
            discogsBtn.classList.remove("pending");
            discogsBtn.classList.remove("inactive");
            discogsBtn.setAttribute("aria-disabled", "false");
            discogsBtn.removeAttribute("target");
            discogsBtn.removeAttribute("rel");
            discogsBtn.title = "Erneut nach Discogs suchen";
        }
        wishlistBtn.setAttribute("aria-disabled", state !== "found" ? "true" : "false");
        wishlistBtn.classList.toggle("inactive", state !== "found");
    };
    const setLibraryState = (state) => {
        if (!state) {
            libraryBadge.className = "discogs-library hidden";
            libraryBadge.textContent = "";
            return;
        }
        libraryBadge.className = `discogs-library ${state}`;
        libraryBadge.textContent = state === "owned" ? "In Sammlung" : "Auf Wunschliste";
    };
    registerTrackElement(key, { index, setDiscogsState, setLibraryState, element: trackDiv });
    setLibraryState(null);
    let manualSearching = false;
    discogsBtn.addEventListener("click", async (ev) => {
        if (discogsBtn.getAttribute("aria-disabled") === "true") {
            ev.preventDefault();
            return;
        }
        if (track.discogsAlbumUrl) {
            return;
        }
        ev.preventDefault();
        if (manualSearching) {
            return;
        }
        manualSearching = true;
        setDiscogsState("pending");
        try {
            const res = await fetch("/api/discogs/search", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    artist: primaryArtist(track.artist) || track.artist,
                    album: normalizeForSearch(track.album) || track.album,
                    releaseYear: track.releaseYear ?? null,
                    track: normalizeForSearch(track.trackName) || track.trackName,
                }),
            });
            if (!res.ok)
                throw new Error(`HTTP ${res.status}`);
            const data = await res.json();
            const url = typeof data?.url === "string" ? data.url : null;
            if (url) {
                markDiscogsResult(key, index, url);
                manualSearching = false;
                window.open(url, "_blank", "noopener");
                return;
            }
        }
        catch (error) {
            console.warn("Discogs-Suche fehlgeschlagen:", error);
        }
        finally {
            if (!track.discogsAlbumUrl) {
                setDiscogsState("not-found");
            }
            manualSearching = false;
        }
    });
    wishlistBtn.addEventListener("click", async (ev) => {
        ev.preventDefault();
        if (wishlistBtn.getAttribute("aria-disabled") === "true") {
            return;
        }
        if (!discogsUiState.loggedIn) {
            alert("Bitte verbinde zuerst deinen Discogs-Account.");
            return;
        }
        const targetUrl = track.discogsAlbumUrl;
        if (!targetUrl) {
            return;
        }
        wishlistBtn.setAttribute("aria-disabled", "true");
        wishlistBtn.classList.add("inactive");
        try {
            const res = await fetch("/api/discogs/wishlist/add", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ url: targetUrl }),
            });
            if (!res.ok) {
                throw new Error(`HTTP ${res.status}`);
            }
            scheduleLibraryRefresh(150);
        }
        catch (e) {
            alert("Konnte nicht zur Wunschliste hinzufügen: " + (e instanceof Error ? e.message : String(e)));
        }
        finally {
            wishlistBtn.setAttribute("aria-disabled", track.discogsAlbumUrl ? "false" : "true");
            wishlistBtn.classList.toggle("inactive", !track.discogsAlbumUrl);
        }
    });
    trackDiv.appendChild(img);
    trackDiv.appendChild(infoDiv);
    trackDiv.appendChild(actions);
    if (initialState === "found" && track.discogsAlbumUrl) {
        setDiscogsState("found", track.discogsAlbumUrl);
    }
    else if (initialState === "not-found") {
        setDiscogsState("not-found");
    }
    else {
        setDiscogsState("pending");
    }
    return trackDiv;
}

function applyViewMode(mode, options = {}) {
    const normalized = mode === "grid" ? "grid" : DEFAULT_VIEW_MODE;
    state.viewMode = normalized;
    storeViewMode(normalized);
    const container = document.getElementById("playlist");
    if (container) {
        container.dataset.viewMode = normalized;
    }
    const buttons = document.querySelectorAll("#view-toggle .view-toggle-btn");
    buttons.forEach((btn) => {
        const btnMode = btn.dataset.mode === "grid" ? "grid" : "list";
        btn.classList.toggle("active", btnMode === normalized);
    });
    if (options?.rerender && state.aggregated) {
        renderTracks(state.aggregated, { reset: true });
    }
}
function initViewToggle() {
    const toggle = document.getElementById("view-toggle");
    if (!toggle || toggle.dataset.bound === "true")
        return;
    toggle.dataset.bound = "true";
    toggle.addEventListener("click", (event) => {
        const target = event.target instanceof HTMLElement
            ? event.target.closest(".view-toggle-btn")
            : null;
        if (!target)
            return;
        const mode = target.dataset.mode === "grid" ? "grid" : "list";
        applyViewMode(mode, { rerender: true });
    });
    applyViewMode(state.viewMode, { rerender: false });
}
function renderTracks(aggregated, options = {}) {
    const container = document.getElementById("playlist");
    if (!container)
        return;
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
        if (!track)
            continue;
        container.appendChild(createTrackElement(track, i));
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
    const loadedOffset = typeof payload?.offset === "number" ? payload.offset : offset;
    const loadedTracks = Array.isArray(payload?.tracks) ? payload.tracks : [];
    if (loadedTracks.length) {
        await queueDiscogsLookups(loadedOffset, loadedTracks);
    }
}
async function loadPlaylist(id, pageSize = DEFAULT_PAGE_SIZE) {
    const header = document.getElementById("playlist-header");
    const container = document.getElementById("playlist");
    if (!header || !container) {
        console.error("Fehlende DOM-Elemente (#playlist-header oder #playlist).");
        return;
    }
    initViewToggle();
    setupDiscogsPanel();
    applyViewMode(state.viewMode, { rerender: false });
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
            await queueDiscogsLookups(0, state.aggregated.tracks);
        }
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
        let loadMoreInFlight = false;
        btn.addEventListener("click", async () => {
            if (!state.aggregated?.hasMore || loadMoreInFlight)
                return;
            loadMoreInFlight = true;
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
                loadMoreInFlight = false;
            }
        }, { once: false });
    }
}
export { loadPlaylist };
