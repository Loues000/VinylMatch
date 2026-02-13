/**
 * Discogs UI Module
 * Handles Discogs login panel, wishlist grid, and library status
 */

import { safeDiscogsUrl, safeDiscogsImage } from "./discogs-state.js";
import { buildTrackKey, getRegistryEntry } from "./track-registry.js";
import { normalizeForSearch, primaryArtist } from "../common/playlist-utils.js";

function emitPlaylistStatus(message, tone = "neutral") {
    try {
        window.dispatchEvent(new CustomEvent("vm:playlist-status", { detail: { message, tone } }));
    }
    catch (_a) {
    }
}

export const discogsUiState = {
    loggedIn: false,
    oauthConfigured: false,
    oauthSession: false,
    username: null,
    name: null,
    wishlist: [],
    wishlistPage: 1,
    wishlistLimit: 12,
    wishlistTotal: 0,
    statuses: new Map(),
    libraryKeyCache: new Map(),
    libraryCacheUser: null,
};

const DISCOGS_TOKEN_STORAGE_KEY = "vinylmatch_discogs_token";
const LIBRARY_KEY_CACHE_STORAGE_KEY = "vinylmatch_discogs_library_cache_v1";
const WISHLIST_PAGE_SIZE = 12;
const LIBRARY_CACHE_MAX_ENTRIES = 2000;
const LIBRARY_CACHE_TTL_MS = 30 * 60 * 1000;
const LIBRARY_CACHE_TTL_EMPTY_MS = 3 * 60 * 1000;
const LIBRARY_STATE_OWNED = "owned";
const LIBRARY_STATE_WISHLIST = "wishlist";
const LIBRARY_STATE_NONE = "none";

let discogsLoginPoll = null;
let libraryRefreshTimer = null;
let autoLoginInFlight = null;
let discogsOAuthPopup = null;
let activePlaylistState = null;

function escapeRegExp(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function normalizeLibraryValue(value) {
    if (!value) return "";
    return normalizeForSearch(value).trim().toLowerCase();
}

function stripArtistPrefixFromTitle(title, artist) {
    if (!title) return "";
    const rawTitle = String(title).trim();
    const artistName = String(artist || "").trim();
    if (!artistName) return rawTitle;
    const escapedArtist = escapeRegExp(artistName);
    if (!escapedArtist) return rawTitle;
    return rawTitle.replace(new RegExp(`^${escapedArtist}\\s*[-:]+\\s*`, "i"), "").trim();
}

function buildArtistAlbumKey(artist, album) {
    const normalizedArtist = normalizeLibraryValue(primaryArtist(artist) || artist);
    const normalizedAlbum = normalizeLibraryValue(album);
    if (!normalizedArtist || !normalizedAlbum) {
        return null;
    }
    return `${normalizedArtist}|${normalizedAlbum}`;
}

function buildWishlistEntryKey(entry) {
    if (!entry) return null;
    const artist = typeof entry.artist === "string" ? entry.artist : "";
    const rawTitle = typeof entry.title === "string" ? entry.title : "";
    const album = stripArtistPrefixFromTitle(rawTitle, artist) || rawTitle;
    return buildArtistAlbumKey(artist, album);
}

function persistLibraryCache() {
    try {
        const now = Date.now();
        const rows = [];
        for (const [key, entry] of discogsUiState.libraryKeyCache.entries()) {
            if (!entry || typeof entry.expiresAt !== "number" || entry.expiresAt <= now) continue;
            rows.push([key, entry.state, entry.expiresAt]);
        }
        rows.sort((a, b) => b[2] - a[2]);
        if (rows.length > LIBRARY_CACHE_MAX_ENTRIES) {
            rows.length = LIBRARY_CACHE_MAX_ENTRIES;
        }
        const payload = {
            v: 1,
            user: discogsUiState.libraryCacheUser || null,
            rows,
        };
        window.localStorage.setItem(LIBRARY_KEY_CACHE_STORAGE_KEY, JSON.stringify(payload));
    } catch (_a) {
    }
}

function loadLibraryCacheFromStorage() {
    try {
        const raw = window.localStorage.getItem(LIBRARY_KEY_CACHE_STORAGE_KEY);
        if (!raw) return;
        const parsed = JSON.parse(raw);
        const rows = Array.isArray(parsed?.rows) ? parsed.rows : [];
        const user = typeof parsed?.user === "string" && parsed.user.trim() ? parsed.user.trim() : null;
        const now = Date.now();
        discogsUiState.libraryKeyCache.clear();
        for (const row of rows) {
            if (!Array.isArray(row) || row.length < 3) continue;
            const key = typeof row[0] === "string" ? row[0] : "";
            const state = row[1];
            const expiresAt = Number(row[2]);
            if (!key || !Number.isFinite(expiresAt) || expiresAt <= now) continue;
            if (state !== LIBRARY_STATE_OWNED && state !== LIBRARY_STATE_WISHLIST && state !== LIBRARY_STATE_NONE) continue;
            discogsUiState.libraryKeyCache.set(key, { state, expiresAt });
        }
        discogsUiState.libraryCacheUser = user;
    } catch (_a) {
        discogsUiState.libraryKeyCache.clear();
        discogsUiState.libraryCacheUser = null;
    }
}

function clearLibraryCache() {
    discogsUiState.libraryKeyCache.clear();
    discogsUiState.libraryCacheUser = null;
    try {
        window.localStorage.removeItem(LIBRARY_KEY_CACHE_STORAGE_KEY);
    } catch (_a) {
    }
}

function ensureLibraryCacheIdentity(username) {
    const user = typeof username === "string" && username.trim() ? username.trim() : null;
    if (!user) {
        return;
    }
    if (!discogsUiState.libraryCacheUser) {
        if (discogsUiState.libraryKeyCache.size > 0) {
            discogsUiState.libraryKeyCache.clear();
        }
        discogsUiState.libraryCacheUser = user;
        persistLibraryCache();
        return;
    }
    if (discogsUiState.libraryCacheUser !== user) {
        clearLibraryCache();
        discogsUiState.libraryCacheUser = user;
        persistLibraryCache();
    }
}

function setCachedLibraryStateByKey(key, state) {
    if (!key) return false;
    const safeState = state === LIBRARY_STATE_OWNED || state === LIBRARY_STATE_WISHLIST || state === LIBRARY_STATE_NONE
        ? state
        : LIBRARY_STATE_NONE;
    const ttl = safeState === LIBRARY_STATE_NONE ? LIBRARY_CACHE_TTL_EMPTY_MS : LIBRARY_CACHE_TTL_MS;
    const nextExpiresAt = Date.now() + ttl;
    const previous = discogsUiState.libraryKeyCache.get(key);
    if (previous && previous.state === safeState && previous.expiresAt >= nextExpiresAt - 1000) {
        return false;
    }
    discogsUiState.libraryKeyCache.set(key, { state: safeState, expiresAt: nextExpiresAt });
    if (discogsUiState.libraryKeyCache.size > LIBRARY_CACHE_MAX_ENTRIES) {
        const sorted = [...discogsUiState.libraryKeyCache.entries()].sort((a, b) => a[1].expiresAt - b[1].expiresAt);
        while (sorted.length > LIBRARY_CACHE_MAX_ENTRIES) {
            const [oldestKey] = sorted.shift();
            discogsUiState.libraryKeyCache.delete(oldestKey);
        }
    }
    return true;
}

function getCachedLibraryStateByKey(key) {
    if (!key) return null;
    const entry = discogsUiState.libraryKeyCache.get(key);
    if (!entry) return null;
    if (typeof entry.expiresAt !== "number" || entry.expiresAt <= Date.now()) {
        discogsUiState.libraryKeyCache.delete(key);
        return null;
    }
    return entry.state;
}

function cacheWishlistEntries(entries) {
    if (!Array.isArray(entries)) return;
    let changed = false;
    for (const item of entries) {
        const key = buildWishlistEntryKey(item);
        if (!key) continue;
        const existing = getCachedLibraryStateByKey(key);
        if (existing === LIBRARY_STATE_OWNED) {
            continue;
        }
        changed = setCachedLibraryStateByKey(key, LIBRARY_STATE_WISHLIST) || changed;
    }
    if (changed) {
        persistLibraryCache();
    }
}

export function rememberLibraryState(artist, album, state) {
    const key = buildArtistAlbumKey(artist, album);
    if (!key) return;
    const nextState = state === LIBRARY_STATE_OWNED ? LIBRARY_STATE_OWNED
        : state === LIBRARY_STATE_WISHLIST ? LIBRARY_STATE_WISHLIST
            : LIBRARY_STATE_NONE;
    const changed = setCachedLibraryStateByKey(key, nextState);
    if (changed) {
        persistLibraryCache();
    }
    if (activePlaylistState) {
        refreshLibraryBadgesLocally(null, activePlaylistState);
    }
}

loadLibraryCacheFromStorage();

function readStoredDiscogsToken() {
    try {
        const raw = window.localStorage.getItem(DISCOGS_TOKEN_STORAGE_KEY);
        if (!raw) {
            return null;
        }
        const token = raw.trim();
        return token || null;
    } catch (_a) {
        return null;
    }
}

function storeDiscogsToken(token) {
    if (!token || typeof token !== "string") {
        return;
    }
    try {
        window.localStorage.setItem(DISCOGS_TOKEN_STORAGE_KEY, token.trim());
    } catch (_a) {
    }
}

function clearStoredDiscogsToken() {
    try {
        window.localStorage.removeItem(DISCOGS_TOKEN_STORAGE_KEY);
    } catch (_a) {
    }
}

function emitDiscogsAuthState() {
    try {
        window.dispatchEvent(new CustomEvent("vm:discogs-auth-state", {
            detail: {
                loggedIn: discogsUiState.loggedIn,
                username: discogsUiState.username,
            },
        }));
    } catch (_a) {
    }
}

function updateTokenHintText() {
    const hint = document.querySelector(".discogs-token .hint");
    if (!hint) {
        return;
    }
    hint.textContent = readStoredDiscogsToken()
        ? "Token locally stored in this browser. Remove it with Disconnect."
        : "The token is stored locally in this browser on this device.";
}

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

function applyOAuthButtonState() {
    const oauthBtn = document.getElementById("discogs-oauth-connect");
    if (!(oauthBtn instanceof HTMLButtonElement)) {
        return;
    }
    const configured = !!discogsUiState.oauthConfigured;
    oauthBtn.disabled = !configured;
    oauthBtn.title = configured ? "Connect with Discogs OAuth" : "Discogs OAuth is not configured on this server";
}

function getWishlistPaging(totalOverride) {
    const totalRaw = typeof totalOverride === "number" ? totalOverride : discogsUiState.wishlistTotal;
    const total = Math.max(0, Number.isFinite(totalRaw) ? totalRaw : 0);
    const limit = Math.max(1, discogsUiState.wishlistLimit || WISHLIST_PAGE_SIZE);
    const totalPages = total > 0 ? Math.max(1, Math.ceil(total / limit)) : 1;
    const safePage = Math.min(totalPages, Math.max(1, discogsUiState.wishlistPage || 1));
    return {
        total,
        limit,
        page: safePage,
        totalPages,
        hasPrev: safePage > 1,
        hasNext: safePage < totalPages,
    };
}

function ensureWishlistPagerControls() {
    const header = document.querySelector(".discogs-wishlist__header");
    if (!header) return null;
    
    let pager = document.getElementById("discogs-wishlist-pager");
    let prev = document.getElementById("discogs-wishlist-prev");
    let next = document.getElementById("discogs-wishlist-next");
    let page = document.getElementById("discogs-wishlist-page");
    
    if (!pager || !prev || !next || !page) {
        pager = document.createElement("div");
        pager.id = "discogs-wishlist-pager";
        pager.className = "discogs-wishlist__pager";
        
        prev = document.createElement("button");
        prev.type = "button";
        prev.id = "discogs-wishlist-prev";
        prev.className = "discogs-wishlist__page-btn";
        prev.setAttribute("aria-label", "Previous wishlist page");
        prev.textContent = "<";
        
        page = document.createElement("span");
        page.id = "discogs-wishlist-page";
        page.className = "discogs-wishlist__page";
        page.textContent = "1/1";
        
        next = document.createElement("button");
        next.type = "button";
        next.id = "discogs-wishlist-next";
        next.className = "discogs-wishlist__page-btn";
        next.setAttribute("aria-label", "Next wishlist page");
        next.textContent = ">";
        
        pager.appendChild(prev);
        pager.appendChild(page);
        pager.appendChild(next);
        header.appendChild(pager);
    }
    
    if (pager.dataset.bound !== "true") {
        pager.dataset.bound = "true";
        prev.addEventListener("click", () => {
            if (discogsUiState.wishlistPage <= 1) return;
            discogsUiState.wishlistPage -= 1;
            refreshWishlistPreview().catch(() => {});
        });
        next.addEventListener("click", () => {
            const paging = getWishlistPaging();
            if (!paging.hasNext) return;
            discogsUiState.wishlistPage += 1;
            refreshWishlistPreview().catch(() => {});
        });
    }
    
    return { pager, prev, next, page };
}

export function renderWishlistGrid(entries, total) {
    const grid = document.getElementById("discogs-wishlist-grid");
    const empty = document.getElementById("discogs-wishlist-empty");
    const count = document.getElementById("discogs-wishlist-count");
    const pager = ensureWishlistPagerControls();
    if (!grid || !empty || !count) return;
    
    discogsUiState.wishlistTotal = Math.max(0, typeof total === "number" ? total : entries.length);
    const paging = getWishlistPaging(discogsUiState.wishlistTotal);
    discogsUiState.wishlistPage = paging.page;
    discogsUiState.wishlistLimit = paging.limit;
    
    if (pager) {
        pager.prev.disabled = !paging.hasPrev;
        pager.next.disabled = !paging.hasNext;
        pager.page.textContent = `${paging.page}/${paging.totalPages}`;
    }
    
    grid.textContent = "";
    if (!entries.length) {
        empty.classList.remove("hidden");
        count.textContent = paging.total > 0 ? `0 von ${paging.total}` : "";
        return;
    }
    
    empty.classList.add("hidden");
    const from = (paging.page - 1) * paging.limit + 1;
    const to = (paging.page - 1) * paging.limit + entries.length;
    count.textContent = paging.total > 0
        ? `${from}-${to} von ${paging.total}`
        : `${entries.length} Eintraege`;
    
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
        discogsUiState.oauthConfigured = !!payload?.oauthConfigured;
        discogsUiState.oauthSession = !!payload?.oauthSession;
        discogsUiState.username = payload?.username || null;
        discogsUiState.name = payload?.name || null;
    } catch (e) {
        discogsUiState.loggedIn = false;
        discogsUiState.oauthConfigured = false;
        discogsUiState.oauthSession = false;
        discogsUiState.username = null;
    } finally {
        ensureLibraryCacheIdentity(discogsUiState.loggedIn ? discogsUiState.username : null);
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
        updateDiscogsChip(discogsUiState.oauthSession ? "Connected (OAuth)" : "Connected", true);
        if (user) {
            user.textContent = discogsUiState.name || discogsUiState.username || "Discogs";
        }
    } else {
        login.classList.remove("hidden");
        dashboard.classList.add("hidden");
        updateDiscogsChip("Not connected", false);
    }
    applyOAuthButtonState();
    emitDiscogsAuthState();
    updateTokenHintText();
}

async function fetchLegacyWishlistBatch() {
    const query = new URLSearchParams({
        limit: "50",
    });
    const res = await fetch(`/api/discogs/wishlist?${query.toString()}`, { cache: "no-cache", credentials: "include" });
    if (!res.ok) {
        throw new Error("HTTP " + res.status);
    }
    const payload = await res.json();
    const items = Array.isArray(payload?.items) ? payload.items : [];
    const total = typeof payload?.total === "number" ? payload.total : items.length;
    return { items, total };
}

function sliceWishlistPage(items, page, limit) {
    const safePage = Math.max(1, page);
    const safeLimit = Math.max(1, limit);
    const start = (safePage - 1) * safeLimit;
    return items.slice(start, start + safeLimit);
}

export async function refreshWishlistPreview(resetPage = false) {
    if (resetPage) {
        discogsUiState.wishlistPage = 1;
    }
    if (!discogsUiState.loggedIn) {
        discogsUiState.wishlist = [];
        discogsUiState.wishlistTotal = 0;
        renderWishlistGrid([], 0);
        if (activePlaylistState) {
            refreshLibraryBadgesLocally(null, activePlaylistState);
        }
        return;
    }
    try {
        const previousTotal = Math.max(0, Number.isFinite(discogsUiState.wishlistTotal) ? discogsUiState.wishlistTotal : 0);
        const previousItems = Array.isArray(discogsUiState.wishlist) ? [...discogsUiState.wishlist] : [];
        const paging = getWishlistPaging();
        const page = Math.max(1, paging.page);
        const limit = Math.max(1, paging.limit);
        const query = new URLSearchParams({
            page: String(page),
            limit: String(limit),
        });
        const res = await fetch(`/api/discogs/wishlist?${query.toString()}`, { cache: "no-cache", credentials: "include" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        const payload = await res.json();
        const items = Array.isArray(payload?.items) ? payload.items : [];
        const total = typeof payload?.total === "number" ? payload.total : items.length;
        const hasPagingMeta = typeof payload?.page === "number" && typeof payload?.limit === "number";
        const payloadPage = hasPagingMeta ? Math.max(1, payload.page) : page;
        const payloadLimit = hasPagingMeta ? Math.max(1, payload.limit) : limit;
        const ignoredPage = hasPagingMeta && page > 1 && total > limit && payloadPage === 1;
        if (!hasPagingMeta || ignoredPage) {
            const legacy = await fetchLegacyWishlistBatch();
            const legacyPageItems = sliceWishlistPage(legacy.items, page, limit);
            const unexpectedLegacyReset = page > 1
                && previousTotal > 0
                && legacy.total === 0
                && legacyPageItems.length === 0;
            if (unexpectedLegacyReset) {
                discogsUiState.wishlistPage = Math.max(1, page - 1);
                discogsUiState.wishlistLimit = limit;
                discogsUiState.wishlist = previousItems;
                renderWishlistGrid(previousItems, previousTotal);
                emitPlaylistStatus("Wishlist page temporarily unavailable. Showing previous page.", "error");
                return;
            }
            discogsUiState.wishlistPage = Math.max(1, page);
            discogsUiState.wishlistLimit = limit;
            discogsUiState.wishlist = legacyPageItems;
            cacheWishlistEntries(legacyPageItems);
            if (legacy.total > 0 && legacyPageItems.length === 0 && discogsUiState.wishlistPage > 1) {
                discogsUiState.wishlistPage -= 1;
                await refreshWishlistPreview(false);
                return;
            }
            renderWishlistGrid(legacyPageItems, legacy.total);
            if (activePlaylistState) {
                refreshLibraryBadgesLocally(null, activePlaylistState);
            }
            return;
        }
        const unexpectedReset = page > 1
            && previousTotal > 0
            && total === 0
            && items.length === 0;
        if (unexpectedReset) {
            discogsUiState.wishlistPage = Math.max(1, page - 1);
            discogsUiState.wishlistLimit = payloadLimit;
            discogsUiState.wishlist = previousItems;
            renderWishlistGrid(previousItems, previousTotal);
            emitPlaylistStatus("Wishlist page temporarily unavailable. Showing previous page.", "error");
            return;
        }
        discogsUiState.wishlistPage = payloadPage;
        discogsUiState.wishlistLimit = payloadLimit;
        discogsUiState.wishlist = items;
        cacheWishlistEntries(items);
        if (total > 0 && items.length === 0 && discogsUiState.wishlistPage > 1) {
            discogsUiState.wishlistPage -= 1;
            await refreshWishlistPreview(false);
            return;
        }
        renderWishlistGrid(items, total);
        if (activePlaylistState) {
            refreshLibraryBadgesLocally(null, activePlaylistState);
        }
    } catch (e) {
        console.warn("Wishlist could not be loaded", e);
    }
}

export function pollForDiscogsLogin(state, timeoutMs = 20000, intervalMs = 1500) {
    if (discogsLoginPoll) {
        window.clearInterval(discogsLoginPoll);
    }
    const start = Date.now();
    discogsLoginPoll = window.setInterval(async () => {
        await refreshDiscogsStatus();
        if (!discogsUiState.loggedIn) {
            await tryAutoLoginFromStorage(state);
        }
        if (discogsUiState.loggedIn) {
            window.clearInterval(discogsLoginPoll);
            discogsLoginPoll = null;
            await refreshWishlistPreview(true);
            scheduleLibraryRefresh(200, state);
        } else if (Date.now() - start > timeoutMs) {
            window.clearInterval(discogsLoginPoll);
            discogsLoginPoll = null;
        }
    }, Math.max(750, intervalMs));
}

async function tryAutoLoginFromStorage(state) {
    if (discogsUiState.loggedIn) {
        return true;
    }
    const storedToken = readStoredDiscogsToken();
    if (!storedToken) {
        return false;
    }
    if (autoLoginInFlight) {
        return autoLoginInFlight;
    }
    autoLoginInFlight = connectDiscogs(state, {
        token: storedToken,
        fromStoredToken: true,
    }).finally(() => {
        autoLoginInFlight = null;
    });
    return autoLoginInFlight;
}

async function refreshDiscogsUiAfterAuth(state) {
    await refreshDiscogsStatus();
    await refreshWishlistPreview(true);
    scheduleLibraryRefresh(200, state);
}

async function startDiscogsOAuth(state) {
    if (!discogsUiState.oauthConfigured) {
        emitPlaylistStatus("Discogs OAuth is not configured on this server.", "error");
        return false;
    }
    try {
        const res = await fetch("/api/discogs/oauth/start", {
            method: "POST",
            credentials: "include",
            cache: "no-cache",
        });
        if (!res.ok) {
            let message = "HTTP " + res.status;
            try {
                const payload = await res.json();
                const apiMessage = payload?.error?.message;
                if (typeof apiMessage === "string" && apiMessage.trim()) {
                    message = apiMessage.trim();
                }
            } catch (_a) {
            }
            throw new Error(message);
        }
        const payload = await res.json();
        const authorizeUrl = typeof payload?.authorizeUrl === "string" ? payload.authorizeUrl : "";
        if (!authorizeUrl) {
            throw new Error("Missing authorizeUrl");
        }
        const popup = window.open(authorizeUrl, "vinylmatch-discogs-oauth", "width=640,height=780");
        if (!popup) {
            emitPlaylistStatus("Please allow popups to connect Discogs with OAuth.", "error");
            return false;
        }
        discogsOAuthPopup = popup;
        popup.focus();
        emitPlaylistStatus("Discogs OAuth started. Complete login/authorize in the popup.");
        pollForDiscogsLogin(state, 90000, 1200);
        return true;
    } catch (error) {
        emitPlaylistStatus("Discogs OAuth start failed: " + (error instanceof Error ? error.message : String(error)), "error");
        return false;
    }
}

function bindDiscogsOAuthCallbackListener(state) {
    if (window.__vmDiscogsOAuthCallbackBound) {
        return;
    }
    window.__vmDiscogsOAuthCallbackBound = true;
    window.addEventListener("message", (event) => {
        const data = event?.data;
        if (!data || data.type !== "discogs-auth-callback") {
            return;
        }
        if (discogsOAuthPopup && !discogsOAuthPopup.closed) {
            try {
                discogsOAuthPopup.close();
            } catch (_a) {
            }
            discogsOAuthPopup = null;
        }
        if (data.success) {
            emitPlaylistStatus("Discogs OAuth connected.", "success");
            refreshDiscogsUiAfterAuth(state).catch(() => {});
        } else {
            const msg = typeof data.message === "string" && data.message.trim()
                ? data.message.trim()
                : "Discogs OAuth failed.";
            emitPlaylistStatus(msg, "error");
        }
    });
}

export async function connectDiscogs(state, options = {}) {
    const tokenFromOptions = typeof options?.token === "string" ? options.token.trim() : "";
    const fromStoredToken = options?.fromStoredToken === true;
    let token = tokenFromOptions;
    const input = document.getElementById("discogs-token");
    if (!token) {
        if (!(input instanceof HTMLInputElement)) {
            return false;
        }
        token = input.value.trim();
    }
    if (!token) {
        if (!fromStoredToken) {
            emitPlaylistStatus("Please paste a Discogs user token first.", "error");
        }
        return false;
    }
    if (input instanceof HTMLInputElement && !fromStoredToken) {
        input.value = "";
    }
    try {
        const res = await fetch("/api/discogs/login", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ token }),
            credentials: "include",
        });
        if (!res.ok) {
            if (fromStoredToken && (res.status === 400 || res.status === 401)) {
                clearStoredDiscogsToken();
            }
            throw new Error("HTTP " + res.status);
        }
        storeDiscogsToken(token);
        await refreshDiscogsStatus();
        await refreshWishlistPreview(true);
        scheduleLibraryRefresh(200, state);
        if (!fromStoredToken) {
            emitPlaylistStatus("Discogs connected.", "success");
        }
        return true;
    } catch (e) {
        if (fromStoredToken) {
            console.warn("Stored Discogs token login failed");
            return false;
        }
        emitPlaylistStatus("Discogs login failed: " + (e instanceof Error ? e.message : String(e)), "error");
        return false;
    } finally {
        updateTokenHintText();
    }
}

export async function disconnectDiscogs() {
    try {
        await fetch("/api/discogs/logout", { method: "POST", credentials: "include" });
    } catch (_) {}
    clearStoredDiscogsToken();
    
    discogsUiState.loggedIn = false;
    discogsUiState.oauthSession = false;
    discogsUiState.username = null;
    discogsUiState.name = null;
    discogsUiState.wishlist = [];
    discogsUiState.wishlistPage = 1;
    discogsUiState.wishlistTotal = 0;
    clearLibraryCache();
    applyDiscogsUi();
    renderWishlistGrid([], 0);
    refreshLibraryBadgesLocally(null, activePlaylistState);
    emitPlaylistStatus("Discogs disconnected.", "success");
}

export async function refreshLibraryStatuses(state) {
    if (!discogsUiState.loggedIn || !Array.isArray(state.aggregated?.tracks)) {
        return;
    }

    // First pass is cache-only (artist + album key), so UI badges update cheaply.
    refreshLibraryBadgesLocally(null, state);

    const unresolvedUrls = [];
    for (const track of state.aggregated.tracks) {
        if (!track || typeof track.discogsAlbumUrl !== "string") continue;
        const safeUrl = safeDiscogsUrl(track.discogsAlbumUrl);
        if (!safeUrl) continue;
        const key = buildArtistAlbumKey(track.artist, track.album);
        const cachedState = key ? getCachedLibraryStateByKey(key) : null;
        if (cachedState && cachedState !== LIBRARY_STATE_WISHLIST) continue;
        unresolvedUrls.push(safeUrl);
    }

    if (!unresolvedUrls.length) {
        return;
    }

    const uniqueUrls = Array.from(new Set(unresolvedUrls)).slice(0, 120);

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
            const safeUrl = safeDiscogsUrl(item.url);
            if (!safeUrl) continue;
            flagMap.set(safeUrl, {
                inWishlist: !!item.inWishlist,
                inCollection: !!item.inCollection,
            });
        }
        refreshLibraryBadgesLocally(flagMap, state);
    } catch (e) {
        console.warn("Failed to fetch Discogs status", e);
    }
}

export function refreshLibraryBadgesLocally(flagMap, state) {
    if (!Array.isArray(state?.aggregated?.tracks)) {
        return;
    }

    if (!discogsUiState.loggedIn) {
        for (let i = 0; i < state.aggregated.tracks.length; i++) {
            const track = state.aggregated.tracks[i];
            if (!track) continue;
            const key = buildTrackKey(track, i);
            const entry = getRegistryEntry(key);
            if (!entry?.setLibraryState) continue;
            entry.setLibraryState(null);
        }
        return;
    }

    let cacheChanged = false;
    for (let i = 0; i < state.aggregated.tracks.length; i++) {
        const track = state.aggregated.tracks[i];
        if (!track || !track.discogsAlbumUrl) continue;

        const key = buildTrackKey(track, i);
        const entry = getRegistryEntry(key);
        if (!entry?.setLibraryState) continue;

        const safeUrl = safeDiscogsUrl(track.discogsAlbumUrl);
        const artistAlbumKey = buildArtistAlbumKey(track.artist, track.album);
        let resolvedState = null;

        if (safeUrl && flagMap?.has(safeUrl)) {
            const flags = flagMap.get(safeUrl);
            if (flags?.inCollection) {
                resolvedState = LIBRARY_STATE_OWNED;
            } else if (flags?.inWishlist) {
                resolvedState = LIBRARY_STATE_WISHLIST;
            } else {
                resolvedState = LIBRARY_STATE_NONE;
            }
            if (artistAlbumKey) {
                cacheChanged = setCachedLibraryStateByKey(artistAlbumKey, resolvedState) || cacheChanged;
            }
        } else if (artistAlbumKey) {
            resolvedState = getCachedLibraryStateByKey(artistAlbumKey);
        }

        if (resolvedState === LIBRARY_STATE_OWNED) {
            entry.setLibraryState("owned");
        } else if (resolvedState === LIBRARY_STATE_WISHLIST) {
            entry.setLibraryState("wishlist");
        } else {
            entry.setLibraryState(null);
        }
    }

    if (cacheChanged) {
        persistLibraryCache();
    }
}

function openDiscogsPopupSafely() {
    const tokenHelpUrl = "https://www.discogs.com/settings/developers";
    try {
        const popup = window.open(tokenHelpUrl, "vinylmatch-discogs-help", "noopener,noreferrer,width=520,height=720");
        if (!popup) {
            emitPlaylistStatus("Please allow popups to open the Discogs login window.", "error");
            console.warn("Discogs login popup blocked by the browser");
            return false;
        }
        popup.opener = null;
        popup.focus();
        console.info("Discogs login popup opened");
        emitPlaylistStatus("Log in on Discogs and copy your user token. Discogs redirects back to the token page after login.");
        return true;
    } catch (error) {
        console.error("Discogs login popup could not be opened", error);
        emitPlaylistStatus("The Discogs login window could not be opened.", "error");
        return false;
    }
}

export function setupDiscogsPanel(state) {
    activePlaylistState = state || null;
    const connectBtn = document.getElementById("discogs-connect");
    const oauthConnectBtn = document.getElementById("discogs-oauth-connect");
    const refreshBtn = document.getElementById("discogs-refresh");
    const disconnectBtn = document.getElementById("discogs-disconnect");
    const popupBtn = document.getElementById("discogs-popup");
    const tokenInput = document.getElementById("discogs-token");
    
    popupBtn?.addEventListener("click", () => {
        openDiscogsPopupSafely();
    });
    
    oauthConnectBtn?.addEventListener("click", () => startDiscogsOAuth(state));
    connectBtn?.addEventListener("click", () => connectDiscogs(state));
    if (tokenInput instanceof HTMLInputElement) {
        tokenInput.addEventListener("keydown", (event) => {
            if (event.key !== "Enter") {
                return;
            }
            event.preventDefault();
            connectDiscogs(state);
        });
    }
    refreshBtn?.addEventListener("click", () => {
        refreshWishlistPreview();
        scheduleLibraryRefresh(100, state);
    });
    disconnectBtn?.addEventListener("click", () => disconnectDiscogs());
    bindDiscogsOAuthCallbackListener(state);
    window.addEventListener("focus", () => {
        refreshDiscogsStatus()
            .then(() => refreshWishlistPreview())
            .then(() => scheduleLibraryRefresh(150, state))
            .catch(() => {});
    });
    
    refreshDiscogsStatus()
        .then(async () => {
            if (!discogsUiState.loggedIn) {
                await tryAutoLoginFromStorage(state);
                await refreshDiscogsStatus();
            }
        })
        .then(() => refreshWishlistPreview(true))
        .then(() => scheduleLibraryRefresh(200, state));
}

