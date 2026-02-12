/**
 * Discogs UI Module
 * Handles Discogs login panel, wishlist grid, and library status
 */

import { safeDiscogsUrl, safeDiscogsImage } from "./discogs-state.js";
import { buildTrackKey, getRegistryEntry } from "./track-registry.js";

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
};

const DISCOGS_TOKEN_STORAGE_KEY = "vinylmatch_discogs_token";
const WISHLIST_PAGE_SIZE = 12;

let discogsLoginPoll = null;
let libraryRefreshTimer = null;
let autoLoginInFlight = null;
let discogsOAuthPopup = null;

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
        updateDiscogsChip(discogsUiState.oauthSession ? "Verbunden (OAuth)" : "Verbunden", true);
        if (user) {
            user.textContent = discogsUiState.name || discogsUiState.username || "Discogs";
        }
    } else {
        login.classList.remove("hidden");
        dashboard.classList.add("hidden");
        updateDiscogsChip("Nicht verbunden", false);
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
            if (legacy.total > 0 && legacyPageItems.length === 0 && discogsUiState.wishlistPage > 1) {
                discogsUiState.wishlistPage -= 1;
                await refreshWishlistPreview(false);
                return;
            }
            renderWishlistGrid(legacyPageItems, legacy.total);
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
        if (total > 0 && items.length === 0 && discogsUiState.wishlistPage > 1) {
            discogsUiState.wishlistPage -= 1;
            await refreshWishlistPreview(false);
            return;
        }
        renderWishlistGrid(items, total);
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
    applyDiscogsUi();
    renderWishlistGrid([], 0);
    refreshLibraryBadgesLocally(null, null);
    emitPlaylistStatus("Discogs disconnected.", "success");
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
        console.warn("Failed to fetch Discogs status", e);
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

