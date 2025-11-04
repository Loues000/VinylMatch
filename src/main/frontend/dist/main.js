import { injectHeader } from "./common/header.js";
import { loadPlaylist } from "./playlist.js";
import { readRecents, storePlaylistChunk, readCachedPlaylist } from "./storage.js";
const PLAYLIST_PAGE_SIZE = 50;
function showGlobalLoading(message = "Playlist wird geladen …") {
    const overlay = document.getElementById("global-loading");
    if (!overlay)
        return;
    overlay.classList.remove("hidden");
    overlay.classList.add("visible");
    const text = document.getElementById("global-loading-text");
    if (text && typeof message === "string") {
        text.textContent = message;
    }
}
function hideGlobalLoading() {
    const overlay = document.getElementById("global-loading");
    if (!overlay)
        return;
    overlay.classList.remove("visible");
    overlay.classList.add("hidden");
}
function createPlaylistCard(item) {
    const card = document.createElement("a");
    card.className = "recent-item";
    card.href = "#";
    card.dataset.playlistId = item.id;
    const img = document.createElement("img");
    img.src = item.coverUrl || "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";
    img.alt = item.name || "Playlist";
    const info = document.createElement("div");
    info.className = "title";
    info.textContent = item.name || "Spotify Playlist";
    const metaText = [];
    if (typeof item.trackCount === "number" && item.trackCount > 0) {
        metaText.push(`${item.trackCount} Songs`);
    }
    if (item.owner && item.owner.trim()) {
        metaText.push(`von ${item.owner}`);
    }
    if (metaText.length > 0) {
        const meta = document.createElement("div");
        meta.className = "meta";
        meta.textContent = metaText.join(" • ");
        const wrapper = document.createElement("div");
        wrapper.appendChild(info);
        wrapper.appendChild(meta);
        card.appendChild(img);
        card.appendChild(wrapper);
    }
    else {
        card.appendChild(img);
        card.appendChild(info);
    }
    return card;
}
function renderRecents() {
    const list = document.getElementById("recent-list");
    const empty = document.querySelector('.sidebar-empty[data-context="recent"]');
    if (!list || !empty)
        return;
    list.textContent = "";
    const recents = readRecents();
    if (!recents.length) {
        empty.classList.remove("hidden");
        return;
    }
    empty.classList.add("hidden");
    for (const item of recents) {
        if (!item?.id)
            continue;
        const card = createPlaylistCard(item);
        card.addEventListener("click", (event) => {
            event.preventDefault();
            loadPlaylistAndNavigate(item.id, { title: item.name });
        });
        list.appendChild(card);
    }
}
function toggleTab(targetId) {
    const tabs = document.querySelectorAll(".sidebar-tab");
    tabs.forEach((tab) => {
        const target = tab.dataset.target;
        const panel = target ? document.getElementById(target) : null;
        const active = target === targetId && !tab.disabled;
        tab.classList.toggle("active", active);
        tab.setAttribute("aria-selected", String(active));
        if (panel) {
            panel.classList.toggle("hidden", !active);
        }
    });
}
window.addEventListener("DOMContentLoaded", () => {
    injectHeader();
    const path = location.pathname.toLowerCase();
    if (path === "/" || path.endsWith("/home.html")) {
        renderRecents();
        const recentTab = document.getElementById("recent-tab");
        const userTab = document.getElementById("user-tab");
        const userPanel = document.getElementById("user-panel");
        const userList = document.getElementById("user-playlist-list");
        const userEmpty = document.querySelector('.sidebar-empty[data-context="user"]');
        let userPlaylists = [];
        let userPlaylistsLoaded = false;
        let userPlaylistsLoading = false;
        let userPlaylistsError = null;
        let isLoggedIn = false;
        let userPlaylistsMeta = null;
        const renderUserPlaylists = () => {
            if (!userPanel || !userList || !userEmpty)
                return;
            userList.textContent = "";
            if (!isLoggedIn) {
                userEmpty.textContent = "Melde dich mit Spotify an, um deine Playlists zu sehen.";
                userEmpty.classList.remove("hidden");
                userPanel.setAttribute("aria-disabled", "true");
                return;
            }
            userPanel.setAttribute("aria-disabled", "false");
            if (userPlaylistsLoading) {
                userEmpty.textContent = "Playlists werden geladen …";
                userEmpty.classList.remove("hidden");
                return;
            }
            if (userPlaylistsError) {
                userEmpty.textContent = userPlaylistsError === "login"
                    ? "Bitte aktualisiere den Login mit Spotify."
                    : "Deine Playlists konnten nicht geladen werden.";
                userEmpty.classList.remove("hidden");
                return;
            }
            if (!userPlaylistsLoaded || userPlaylists.length === 0) {
                userEmpty.textContent = "Keine Playlists gefunden.";
                userEmpty.classList.remove("hidden");
                return;
            }
            userEmpty.classList.add("hidden");
            for (const playlist of userPlaylists) {
                if (!playlist?.id)
                    continue;
                const card = createPlaylistCard(playlist);
                card.addEventListener("click", (event) => {
                    event.preventDefault();
                    loadPlaylistAndNavigate(playlist.id, { title: playlist.name });
                });
                userList.appendChild(card);
            }
            if (userPlaylistsMeta?.hasMore) {
                const hint = document.createElement("p");
                hint.className = "sidebar-empty";
                hint.style.marginTop = "8px";
                hint.textContent = "Es werden nur die ersten 50 Playlists angezeigt.";
                userList.appendChild(hint);
            }
        };
        const fetchUserPlaylists = async () => {
            if (!isLoggedIn || userPlaylistsLoading || userPlaylistsLoaded)
                return;
            userPlaylistsLoading = true;
            userPlaylistsError = null;
            renderUserPlaylists();
            try {
                const res = await fetch("/api/user/playlists?limit=50", { cache: "no-cache" });
                if (res.status === 401) {
                    userPlaylistsError = "login";
                    return;
                }
                if (!res.ok) {
                    throw new Error(`HTTP ${res.status}`);
                }
                const data = await res.json();
                if (Array.isArray(data?.items)) {
                    userPlaylists = data.items;
                    userPlaylistsLoaded = true;
                    userPlaylistsMeta = {
                        hasMore: !!data.hasMore,
                        total: typeof data.total === "number" ? data.total : data.items.length,
                    };
                }
                else {
                    userPlaylists = [];
                    userPlaylistsLoaded = true;
                    userPlaylistsMeta = null;
                }
            }
            catch (e) {
                console.warn("Konnte Benutzer-Playlists nicht laden:", e);
                userPlaylistsError = "error";
            }
            finally {
                userPlaylistsLoading = false;
                renderUserPlaylists();
            }
        };
        const handleAuthChange = (loggedIn) => {
            isLoggedIn = !!loggedIn;
            if (userTab) {
                userTab.disabled = !isLoggedIn;
                userTab.setAttribute("aria-selected", "false");
            }
            if (userPanel) {
                userPanel.classList.add("hidden");
                if (!isLoggedIn) {
                    userPanel.setAttribute("aria-disabled", "true");
                }
            }
            if (!isLoggedIn) {
                userPlaylists = [];
                userPlaylistsLoaded = false;
                userPlaylistsError = null;
                userPlaylistsMeta = null;
                if (recentTab)
                    toggleTab("recent-panel");
            }
            renderUserPlaylists();
            if (isLoggedIn) {
                fetchUserPlaylists();
            }
        };
        window.addEventListener("vm:auth-state", (event) => {
            handleAuthChange(!!event?.detail?.loggedIn);
        });
        if (recentTab) {
            recentTab.addEventListener("click", (event) => {
                event.preventDefault();
                toggleTab("recent-panel");
            });
        }
        if (userTab) {
            userTab.addEventListener("click", (event) => {
                event.preventDefault();
                if (userTab.disabled) {
                    handleAuthChange(false);
                    return;
                }
                toggleTab("user-panel");
                fetchUserPlaylists();
            });
        }
        toggleTab("recent-panel");
        const btn = document.getElementById("use-link");
        const ta = document.getElementById("playlist-url");
        const gen = document.getElementById("generated-links");
        const apiA = document.getElementById("api-link");
        const pageA = document.getElementById("playlist-page-link");
        let submitting = false;
        if (btn && ta) {
            btn.addEventListener("click", async () => {
                if (submitting)
                    return;
                const url = ta.value.trim();
                if (!url) {
                    alert("Please paste a Spotify playlist link first.");
                    return;
                }
                let id;
                try {
                    const parsed = new URL(url);
                    if (!parsed.hostname.includes("spotify.com"))
                        throw new Error("Not a Spotify URL");
                    const parts = parsed.pathname.split("/");
                    if (parts[1] !== "playlist" || parts.length < 3)
                        throw new Error("Not a playlist URL");
                    id = parts[2].split("?")[0];
                    if (!id)
                        throw new Error("Invalid ID");
                }
                catch (e) {
                    alert("Invalid Spotify playlist URL: " + (e instanceof Error ? e.message : String(e)));
                    return;
                }
                const apiUrl = `/api/playlist?id=${encodeURIComponent(id)}&limit=${PLAYLIST_PAGE_SIZE}`;
                const pageUrl = `/playlist.html?id=${encodeURIComponent(id)}`;
                if (apiA) {
                    apiA.href = apiUrl;
                    apiA.textContent = `${location.origin}${apiUrl}`;
                }
                if (pageA) {
                    pageA.href = pageUrl;
                    pageA.textContent = pageUrl;
                }
                if (gen)
                    gen.classList.add("visible");
                submitting = true;
                btn.disabled = true;
                showGlobalLoading("Playlist wird geladen …");
                let response;
                try {
                    response = await fetch(apiUrl, { cache: "no-cache" });
                    if (!response.ok) {
                        throw new Error(`HTTP ${response.status}`);
                    }
                    const payload = await response.json();
                    const cached = readCachedPlaylist();
                    storePlaylistChunk(id, payload, cached?.id === id ? cached.data : undefined);
                    renderRecents();
                    location.href = pageUrl;
                }
                catch (e) {
                    hideGlobalLoading();
                    let msg = e instanceof Error ? e.message : String(e);
                    if (response?.status === 401) {
                        msg = "Bitte melde dich zuerst mit Spotify an.";
                    }
                    alert("Playlist konnte nicht geladen werden: " + msg);
                }
                finally {
                    submitting = false;
                    btn.disabled = false;
                }
            });
        }
    }
    else if (path.endsWith("/playlist.html")) {
        const urlParams = new URLSearchParams(location.search);
        const id = urlParams.get("id");
        if (!id) {
            loadPlaylist();
            return;
        }
        loadPlaylist(id, PLAYLIST_PAGE_SIZE);
    }
});
async function loadPlaylistAndNavigate(id, options = {}) {
    if (!id)
        return;
    const pageUrl = `/playlist.html?id=${encodeURIComponent(id)}`;
    showGlobalLoading(options.title ? `${options.title} wird geladen …` : "Playlist wird geladen …");
    let response;
    try {
        const query = `/api/playlist?id=${encodeURIComponent(id)}&limit=${PLAYLIST_PAGE_SIZE}`;
        response = await fetch(query, { cache: "no-cache" });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const payload = await response.json();
        const cached = readCachedPlaylist();
        storePlaylistChunk(id, payload, cached?.id === id ? cached.data : undefined);
        renderRecents();
        location.href = pageUrl;
    }
    catch (e) {
        hideGlobalLoading();
        let msg = e instanceof Error ? e.message : String(e);
        if (response?.status === 401) {
            msg = "Bitte melde dich zuerst mit Spotify an.";
        }
        alert("Playlist konnte nicht geöffnet werden: " + msg);
    }
}
