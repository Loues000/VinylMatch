import { injectHeader } from "./common/header.js";
import { loadPlaylist } from "./playlist.js";
window.addEventListener("DOMContentLoaded", () => {
    injectHeader();
    const path = location.pathname.toLowerCase();
    if (path === "/" || path.endsWith("/home.html")) {
        // Render recent playlists sidebar
        try {
            const raw = localStorage.getItem("vm:recentPlaylists");
            const recents = raw ? JSON.parse(raw) : [];
            const list = document.getElementById("recent-list");
            if (list && Array.isArray(recents) && recents.length > 0) {
                list.textContent = "";
                for (const item of recents) {
                    const a = document.createElement("a");
                    a.className = "recent-item";
                    a.href = `/playlist.html?id=${encodeURIComponent(item.id)}`;
                    const img = document.createElement("img");
                    img.src = item.coverUrl || "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";
                    img.alt = item.name;
                    const title = document.createElement("div");
                    title.className = "title";
                    title.textContent = item.name;
                    a.appendChild(img);
                    a.appendChild(title);
                    list.appendChild(a);
                }
            }
        }
        catch (e) {
            console.warn("Failed to render recent playlists:", e);
        }
        const btn = document.getElementById("use-link");
        const ta = document.getElementById("playlist-url");
        const gen = document.getElementById("generated-links");
        const apiA = document.getElementById("api-link");
        const pageA = document.getElementById("playlist-page-link");
        if (btn && ta) {
            btn.addEventListener("click", async () => {
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
                // Generate and show links without prefetching
                const apiUrl = `/api/playlist?id=${encodeURIComponent(id)}`;
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
                    gen.style.display = "block";
                location.href = pageUrl;
                // Update recent playlists
                try {
                    const raw = localStorage.getItem("vm:recentPlaylists");
                    const recents = raw ? JSON.parse(raw) : [];
                    // Check if this ID already exists
                    const existingIndex = recents.findIndex((p) => p.id === id);
                    if (existingIndex >= 0) {
                        // Update existing entry
                        recents[existingIndex].name = "Spotify Playlist"; // Placeholder, should fetch name/cover later
                        recents[existingIndex].coverUrl = null; // Placeholder, should fetch cover later
                    }
                    else {
                        // Add new entry
                        recents.unshift({ id, name: "Spotify Playlist", coverUrl: null });
                        if (recents.length > 10)
                            recents.length = 10; // Limit to 10 recent playlists
                    }
                    localStorage.setItem("vm:recentPlaylists", JSON.stringify(recents));
                }
                catch (e) {
                    console.warn("Failed to update recent playlists:", e);
                }
            });
        }
    }
    else if (path.endsWith("/playlist.html")) {
        const urlParams = new URLSearchParams(location.search);
        const id = urlParams.get("id");
        if (!id) {
            // Keine ID -> versuche aus Cache zu laden
            loadPlaylist();
            return;
        }
        loadPlaylist(id);
    }
});