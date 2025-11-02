// 1x1 transparentes GIF als Fallback (verhindert kaputtes Bild-Icon)
const PLACEHOLDER_IMG = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";
// LocalStorage Keys zum Persistieren der letzten Playlist
const LS_LAST_ID = "vm:lastPlaylistId";
const LS_LAST_DATA = "vm:lastPlaylistData";
const LS_RECENTS = "vm:recentPlaylists";
function updateRecents(id, playlist) {
    try {
        const item = {
            id,
            name: playlist.playlistName,
            coverUrl: playlist.playlistCoverUrl || null,
        };
        const raw = localStorage.getItem(LS_RECENTS);
        let arr = raw ? JSON.parse(raw) : [];
        // Entferne vorhandenes mit gleicher ID
        arr = arr.filter((x) => x.id !== id);
        // Neu an den Anfang
        arr.unshift(item);
        // Maximal 9 Einträge behalten
        if (arr.length > 9)
            arr = arr.slice(0, 9);
        localStorage.setItem(LS_RECENTS, JSON.stringify(arr));
    }
    catch (e) {
        console.warn("Konnte Recents nicht aktualisieren:", e);
    }
}
function renderPlaylist(playlist) {
    const header = document.getElementById("playlist-header");
    const container = document.getElementById("playlist");
    if (!header || !container)
        return;
    // Body-Style (falls gewünscht)
    document.body.style.backgroundColor = "white";
    // Header leeren und neu aufbauen
    header.textContent = "";
    const coverImg = document.createElement("img");
    coverImg.id = "playlist-cover";
    coverImg.src = playlist.playlistCoverUrl || PLACEHOLDER_IMG;
    coverImg.alt = "Playlist Cover";
    const headerInfo = document.createElement("div");
    headerInfo.className = "header-info";
    const titleDiv = document.createElement("div");
    titleDiv.className = "playlist-title";
    if (playlist.playlistUrl) {
        const a = document.createElement("a");
        a.href = playlist.playlistUrl;
        a.target = "_blank";
        a.rel = "noopener noreferrer";
        a.textContent = playlist.playlistName;
        a.style.color = "inherit";
        a.style.textDecoration = "none";
        titleDiv.appendChild(a);
    }
    else {
        titleDiv.textContent = playlist.playlistName;
    }
    const subtitleDiv = document.createElement("div");
    subtitleDiv.className = "playlist-subtitle";
    subtitleDiv.textContent = `${playlist.tracks.length} Songs`;
    headerInfo.appendChild(titleDiv);
    headerInfo.appendChild(subtitleDiv);
    header.appendChild(coverImg);
    header.appendChild(headerInfo);
    // Vorherige Virtualisierung bereinigen, falls vorhanden
    container.classList.remove("virtualized-playlist");
    if (typeof container._virtualCleanup === "function") {
        try {
            container._virtualCleanup();
        }
        catch (_a) {
            // Ignoriere Fehler beim Bereinigen
        }
        container._virtualCleanup = undefined;
    }
    // Trackliste erzeugen (virtualisiert)
    container.textContent = ""; // vorherigen Inhalt entfernen
    const tracks = Array.isArray(playlist.tracks) ? playlist.tracks : [];
    if (tracks.length === 0) {
        const empty = document.createElement("div");
        empty.className = "playlist-empty";
        empty.textContent = "Keine Songs gefunden.";
        container.appendChild(empty);
        return;
    }
    container.classList.add("virtualized-playlist");
    const createTrackElement = (track) => {
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
            a.style.color = "inherit";
            a.style.textDecoration = "none";
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
        trackDiv.appendChild(img);
        trackDiv.appendChild(infoDiv);
        const actions = document.createElement("div");
        actions.className = "actions";
        actions.style.marginLeft = "auto";
        const makeBtn = (active) => {
            const a = document.createElement("a");
            a.textContent = "🔍";
            a.style.display = "inline-flex";
            a.style.alignItems = "center";
            a.style.justifyContent = "center";
            a.style.width = "28px";
            a.style.height = "28px";
            a.style.border = "1px solid";
            a.style.borderRadius = "6px";
            a.style.background = "#fff";
            a.style.textDecoration = "none";
            a.style.userSelect = "none";
            const setActive = (on) => {
                if (on) {
                    a.title = "Auf Discogs ansehen";
                    a.style.opacity = "1";
                    a.style.borderColor = "#888";
                    a.style.color = "#333";
                    a.style.cursor = "pointer";
                    a.setAttribute("aria-disabled", "false");
                }
                else {
                    a.title = "Discogs suchen";
                    a.style.opacity = "0.5";
                    a.style.borderColor = "#bbb";
                    a.style.color = "#999";
                    a.style.cursor = "pointer";
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
                        if (a._setActive) {
                            a._setActive(true);
                        }
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
        trackDiv.appendChild(actions);
        return trackDiv;
    };
    const viewport = document.createElement("div");
    viewport.className = "playlist-viewport";
    viewport.style.willChange = "transform";
    const spacer = document.createElement("div");
    spacer.className = "playlist-spacer";
    spacer.setAttribute("aria-hidden", "true");
    container.appendChild(spacer);
    container.appendChild(viewport);
    const measureRowHeight = () => {
        if (!tracks.length)
            return 0;
        const probe = createTrackElement(tracks[0]);
        probe.style.visibility = "hidden";
        probe.style.position = "absolute";
        probe.style.pointerEvents = "none";
        viewport.appendChild(probe);
        const height = probe.getBoundingClientRect().height;
        viewport.removeChild(probe);
        return height || 0;
    };
    let rowHeight = measureRowHeight() || 100;
    let totalHeight = rowHeight * tracks.length;
    spacer.style.height = `${totalHeight}px`;
    const VISIBLE_COUNT = 30;
    const OVERSCAN = 6;
    let currentStart = -1;
    let currentEnd = -1;
    const renderSlice = (start, end) => {
        viewport.textContent = "";
        const frag = document.createDocumentFragment();
        for (let i = start; i < end; i++) {
            const node = createTrackElement(tracks[i]);
            frag.appendChild(node);
        }
        viewport.appendChild(frag);
    };
    const update = () => {
        const rect = container.getBoundingClientRect();
        const containerOffsetTop = window.scrollY + rect.top;
        const scrollTop = Math.max(0, window.scrollY - containerOffsetTop);
        const viewHeight = window.innerHeight;
        totalHeight = rowHeight * tracks.length;
        const visibleItems = Math.max(VISIBLE_COUNT, Math.ceil(viewHeight / rowHeight) + OVERSCAN * 2);
        const maxStart = Math.max(0, tracks.length - visibleItems);
        const baseStart = Math.max(0, Math.floor(scrollTop / rowHeight) - OVERSCAN);
        const nextStart = Math.min(baseStart, maxStart);
        const baseEnd = Math.max(nextStart + visibleItems, Math.ceil((scrollTop + viewHeight) / rowHeight) + OVERSCAN);
        const nextEnd = Math.min(tracks.length, baseEnd);
        if (nextStart === currentStart && nextEnd === currentEnd)
            return;
        currentStart = nextStart;
        currentEnd = nextEnd;
        viewport.style.transform = `translateY(${nextStart * rowHeight}px)`;
        renderSlice(nextStart, nextEnd);
    };
    let scheduled = false;
    const schedule = () => {
        if (scheduled)
            return;
        scheduled = true;
        requestAnimationFrame(() => {
            scheduled = false;
            update();
        });
    };
    const onScroll = () => schedule();
    const onResize = () => {
        rowHeight = measureRowHeight() || rowHeight;
        totalHeight = rowHeight * tracks.length;
        spacer.style.height = `${totalHeight}px`;
        schedule();
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onResize);
    container._virtualCleanup = () => {
        window.removeEventListener("scroll", onScroll);
        window.removeEventListener("resize", onResize);
    };
    update();
}
async function loadPlaylist(id) {
    const header = document.getElementById("playlist-header");
    const container = document.getElementById("playlist");
    if (!header || !container) {
        console.error("Fehlende DOM-Elemente (#playlist-header oder #playlist). ");
        return;
    }
    // Wenn eine ID übergeben wurde: live laden, rendern, cachen
    if (id && id.trim() !== "") {
        container.textContent = "Loading...";
        try {
            const url = `/api/playlist?id=${encodeURIComponent(id)}`;
            const response = await fetch(url);
            if (!response.ok) {
                throw new Error(`HTTP ${response.status} ${response.statusText}`);
            }
            const playlist = await response.json();
            renderPlaylist(playlist);
            // Erfolgreich geladen -> in LocalStorage cachen
            try {
                localStorage.setItem(LS_LAST_ID, id);
                localStorage.setItem(LS_LAST_DATA, JSON.stringify(playlist));
                updateRecents(id, playlist);
            }
            catch (e) {
                // Ignoriere Storage-Fehler
                console.warn("Konnte Playlist nicht in localStorage speichern:", e);
            }
        }
        catch (err) {
            console.error("Fehler beim Laden der Playlist:", err);
            const msg = err instanceof Error ? err.message : String(err);
            // Fallback: versuche gecachte Playlist anzuzeigen
            try {
                const cached = localStorage.getItem(LS_LAST_DATA);
                if (cached) {
                    const playlist = JSON.parse(cached);
                    renderPlaylist(playlist);
                    return;
                }
            }
            catch (_) { }
            // Kein Cache vorhanden -> Fehlermeldung anzeigen
            container.textContent = `Fehler beim Laden der Playlist: ${msg}`;
        }
        return;
    }
    // Keine ID: versuche aus Cache zu rendern
    try {
        const cached = localStorage.getItem(LS_LAST_DATA);
        if (cached) {
            const playlist = JSON.parse(cached);
            renderPlaylist(playlist);
            return;
        }
    }
    catch (e) {
        console.warn("Fehler beim Lesen der gespeicherten Playlist:", e);
    }
    // Weder ID noch Cache vorhanden
    container.textContent = "No playlist ID provided and no cached playlist available.";
}
export { loadPlaylist };