/**
 * Discogs UI Module
 * Handles Discogs login panel, wishlist grid, and library status
 */

import { safeDiscogsUrl, safeDiscogsImage } from "./discogs-state.js";
import { buildTrackKey, getRegistryEntry } from "./track-registry.js";

export const discogsUiState = {
    loggedIn: false,
    username: null,
    name: null,
    wishlist: [],
    statuses: new Map(),
};

let discogsLoginPoll = null;
let libraryRefreshTimer = null;

export function scheduleLibraryRefresh(delay = 300, state) {
    if (libraryRefreshTimer) {
        clearTimeout(libraryRefreshTimer);
    }
    libraryRefreshTimer = window.setTimeout(() => refreshLibraryStatuses(state).catch(() => {}), delay);
}

function updateDiscogsChip(text, online) {
    const chip = document.getElementById("discogs-status");
    if (!chip) return;
    chip.textContent = text;
    chip.classList.toggle("offline", !online);
}

export function renderWishlistGrid(entries, total) {
    const grid = document.getElementById("discogs-wishlist-grid");
    const empty = document.getElementById("discogs-wishlist-empty");
    const count = document.getElementById("discogs-wishlist-count");
    if (!grid || !empty || !count) return;
    
    grid.textContent = "";
    if (!entries.length) {
        empty.classList.remove("hidden");
        count.textContent = "";
        return;
    }
    
    empty.classList.add("hidden");
    count.textContent = total ? `${Math.min(entries.length, total)} von ${total}` : `${entries.length} Einträge`;
    
    for (const item of entries) {
        const safeUrl = safeDiscogsUrl(item.url);
        const safeThumb = safeDiscogsImage(item.thumb);
        
        const card = document.createElement("article");
        card.className = "discogs-wishlist__item";
        
        if (safeThumb) {
            const img = document.createElement("img");
            img.src = safeThumb;
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
        
        if (safeUrl) {
            const link = document.createElement("a");
            link.href = safeUrl;
            link.target = "_blank";
            link.rel = "noopener noreferrer";
            link.textContent = "Auf Discogs öffnen";
            card.appendChild(link);
        }
        grid.appendChild(card);
    }
}

export async function refreshDiscogsStatus() {
    try {
        const res = await fetch("/api/discogs/status", { cache: "no-cache", credentials: "include" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        const payload = await res.json();
        discogsUiState.loggedIn = !!payload?.loggedIn;
        discogsUiState.username = payload?.username || null;
        discogsUiState.name = payload?.name || null;
    } catch (e) {
        discogsUiState.loggedIn = false;
        discogsUiState.username = null;
    } finally {
        applyDiscogsUi();
    }
}

export function applyDiscogsUi() {
    const login = document.getElementById("discogs-login");
    const dashboard = document.getElementById("discogs-dashboard");
    const user = document.getElementById("discogs-user");
    if (!login || !dashboard) return;
    
    if (discogsUiState.loggedIn) {
        login.classList.add("hidden");
        dashboard.classList.remove("hidden");
        updateDiscogsChip("Verbunden", true);
        if (user) {
            user.textContent = discogsUiState.name || discogsUiState.username || "Discogs";
        }
    } else {
        login.classList.remove("hidden");
        dashboard.classList.add("hidden");
        updateDiscogsChip("Nicht verbunden", false);
    }
}

export async function refreshWishlistPreview() {
    if (!discogsUiState.loggedIn) {
        renderWishlistGrid([], 0);
        return;
    }
    try {
        const res = await fetch("/api/discogs/wishlist?limit=12", { cache: "no-cache", credentials: "include" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        const payload = await res.json();
        const items = Array.isArray(payload?.items) ? payload.items : [];
        discogsUiState.wishlist = items;
        renderWishlistGrid(items, payload?.total ?? items.length);
    } catch (e) {
        console.warn("Wunschliste konnte nicht geladen werden", e);
    }
}

export function pollForDiscogsLogin(state, timeoutMs = 20000, intervalMs = 1500) {
    if (discogsLoginPoll) {
        window.clearInterval(discogsLoginPoll);
    }
    const start = Date.now();
    discogsLoginPoll = window.setInterval(async () => {
        await refreshDiscogsStatus();
        if (discogsUiState.loggedIn) {
            window.clearInterval(discogsLoginPoll);
            discogsLoginPoll = null;
            await refreshWishlistPreview();
            scheduleLibraryRefresh(200, state);
        } else if (Date.now() - start > timeoutMs) {
            window.clearInterval(discogsLoginPoll);
            discogsLoginPoll = null;
        }
    }, Math.max(750, intervalMs));
}

export async function connectDiscogs(state) {
    const input = document.getElementById("discogs-token");
    if (!(input instanceof HTMLInputElement)) return;
    
    const token = input.value.trim();
    if (!token) return;
    input.value = "";
    
    try {
        const res = await fetch("/api/discogs/login", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ token }),
            credentials: "include",
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await refreshDiscogsStatus();
        await refreshWishlistPreview();
        scheduleLibraryRefresh(200, state);
    } catch (e) {
        alert("Discogs-Login fehlgeschlagen: " + (e instanceof Error ? e.message : String(e)));
    }
}

export async function disconnectDiscogs() {
    try {
        await fetch("/api/discogs/logout", { method: "POST", credentials: "include" });
    } catch (_) {}
    
    discogsUiState.loggedIn = false;
    discogsUiState.username = null;
    discogsUiState.name = null;
    discogsUiState.wishlist = [];
    applyDiscogsUi();
    renderWishlistGrid([], 0);
    refreshLibraryBadgesLocally(null, null);
}

export async function refreshLibraryStatuses(state) {
    if (!discogsUiState.loggedIn || !Array.isArray(state.aggregated?.tracks)) {
        return;
    }
    
    const urls = state.aggregated.tracks
        .map((track) => track?.discogsAlbumUrl)
        .map((url) => (typeof url === "string" ? safeDiscogsUrl(url) : null))
        .filter((url) => !!url);
    
    if (!urls.length) {
        refreshLibraryBadgesLocally(null, state);
        return;
    }
    
    const uniqueUrls = Array.from(new Set(urls)).slice(0, 120);
    
    try {
        const res = await fetch("/api/discogs/library-status", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ urls: uniqueUrls }),
            credentials: "include",
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        
        const payload = await res.json();
        const results = Array.isArray(payload?.results) ? payload.results : [];
        
        const flagMap = new Map();
        for (const item of results) {
            if (!item || typeof item.url !== "string") continue;
            flagMap.set(item.url, {
                inWishlist: !!item.inWishlist,
                inCollection: !!item.inCollection,
            });
        }
        refreshLibraryBadgesLocally(flagMap, state);
    } catch (e) {
        console.warn("Konnte Discogs-Status nicht abrufen", e);
    }
}

export function refreshLibraryBadgesLocally(flagMap, state) {
    if (!Array.isArray(state?.aggregated?.tracks)) {
        return;
    }
    
    for (let i = 0; i < state.aggregated.tracks.length; i++) {
        const track = state.aggregated.tracks[i];
        if (!track || !track.discogsAlbumUrl) continue;
        
        const key = buildTrackKey(track, i);
        const entry = getRegistryEntry(key);
        if (!entry?.setLibraryState) continue;
        
        const flags = flagMap?.get(track.discogsAlbumUrl);
        if (flags?.inCollection) {
            entry.setLibraryState("owned");
        } else if (flags?.inWishlist) {
            entry.setLibraryState("wishlist");
        } else {
            entry.setLibraryState(null);
        }
    }
}

function openDiscogsPopupSafely() {
    const loginUrl = "https://www.discogs.com/login";
    try {
        const popup = window.open(loginUrl, "_blank", "noopener,noreferrer,width=520,height=720");
        if (!popup) {
            alert("Bitte erlaube Popups, um dich bei Discogs anzumelden.");
            console.warn("Discogs login popup blocked by the browser");
            return false;
        }
        popup.opener = null;
        popup.focus();
        console.info("Discogs login popup opened");
        return true;
    } catch (error) {
        console.error("Discogs login popup konnte nicht geöffnet werden", error);
        alert("Das Discogs-Anmeldefenster konnte nicht geöffnet werden.");
        return false;
    }
}

export function setupDiscogsPanel(state) {
    const connectBtn = document.getElementById("discogs-connect");
    const refreshBtn = document.getElementById("discogs-refresh");
    const disconnectBtn = document.getElementById("discogs-disconnect");
    const popupBtn = document.getElementById("discogs-popup");
    
    popupBtn?.addEventListener("click", () => {
        const opened = openDiscogsPopupSafely();
        if (opened) pollForDiscogsLogin(state);
    });
    
    connectBtn?.addEventListener("click", () => connectDiscogs(state));
    refreshBtn?.addEventListener("click", () => {
        refreshWishlistPreview();
        scheduleLibraryRefresh(100, state);
    });
    disconnectBtn?.addEventListener("click", () => disconnectDiscogs());
    
    refreshDiscogsStatus()
        .then(() => refreshWishlistPreview())
        .then(() => scheduleLibraryRefresh(200, state));
}
