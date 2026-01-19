/**
 * Track Renderer Module
 * Creates track elements and handles quality badges
 */

import { primaryArtist, normalizeForSearch } from "../common/playlist-utils.js";
import { getVendors, buildVendorUrl } from "../common/vendors.js";
import { buildTrackKey, registerTrackElement } from "./track-registry.js";
import { safeDiscogsUrl, markDiscogsResult, discogsState } from "./discogs-state.js";
import { discogsUiState, scheduleLibraryRefresh } from "./discogs-ui.js";

const PLACEHOLDER_IMG = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";

export function determineMatchQuality(url) {
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

export function createQualityBadge(quality) {
    const badge = document.createElement("span");
    badge.className = `match-quality match-quality--${quality.level}`;
    badge.textContent = quality.label;
    return badge;
}

export function buildVendorLinks(track) {
    const vendors = getVendors();
    return vendors.map((vendor) => {
        const url = buildVendorUrl(vendor, track);
        const b = document.createElement("a");
        b.textContent = vendor.label;
        b.title = vendor.name;
        b.setAttribute("aria-label", vendor.name);
        b.classList.add("vendor-link");
        
        if (url) {
            b.href = url;
            b.target = "_blank";
            b.rel = "noopener noreferrer";
            b.classList.remove("inactive");
            b.setAttribute("aria-disabled", "false");
        } else {
            b.href = "#";
            b.classList.add("inactive");
            b.setAttribute("aria-disabled", "true");
        }
        return b;
    });
}

export function createTrackElement(track, index, state) {
    const key = buildTrackKey(track, index);
    const initialDiscogsUrl = safeDiscogsUrl(track.discogsAlbumUrl);
    track.discogsAlbumUrl = initialDiscogsUrl;
    
    const initialState = initialDiscogsUrl
        ? "found"
        : (track.discogsStatus === "not-found" ? "not-found" : "pending");
    
    const initialQuality = initialDiscogsUrl
        ? determineMatchQuality(initialDiscogsUrl)
        : (initialState === "not-found"
            ? { level: "poor", label: "Kein Treffer" }
            : { level: "pending", label: "Wird gesucht…" });
    
    const trackDiv = document.createElement("div");
    trackDiv.className = "track";
    if (key) trackDiv.dataset.trackKey = key;
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
    } else {
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
    
    const setDiscogsState = (discogsStateVal, url) => {
        const safeUrl = url ? safeDiscogsUrl(url) : null;
        trackDiv.dataset.discogsState = discogsStateVal;
        
        if (discogsStateVal === "found" && safeUrl) {
            const quality = determineMatchQuality(safeUrl);
            updateBadge(quality);
            track.discogsAlbumUrl = safeUrl;
            track.discogsStatus = "found";
            discogsBtn.classList.remove("inactive", "pending");
            discogsBtn.setAttribute("aria-disabled", "false");
            discogsBtn.href = safeUrl;
            discogsBtn.target = "_blank";
            discogsBtn.rel = "noopener noreferrer";
            discogsBtn.title = quality.level === "good" ? "Auf Discogs ansehen" : "Discogs-Suchergebnis";
        } else if (discogsStateVal === "pending") {
            updateBadge({ level: "pending", label: "Wird gesucht…" });
            track.discogsStatus = "pending";
            discogsBtn.href = "#";
            discogsBtn.classList.add("inactive", "pending");
            discogsBtn.setAttribute("aria-disabled", "true");
            discogsBtn.removeAttribute("target");
            discogsBtn.removeAttribute("rel");
            discogsBtn.title = "Discogs-Suche läuft …";
        } else {
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
        
        wishlistBtn.setAttribute("aria-disabled", discogsStateVal !== "found" ? "true" : "false");
        wishlistBtn.classList.toggle("inactive", discogsStateVal !== "found");
    };
    
    const setLibraryState = (libraryState) => {
        if (!libraryState) {
            libraryBadge.className = "discogs-library hidden";
            libraryBadge.textContent = "";
            return;
        }
        libraryBadge.className = `discogs-library ${libraryState}`;
        libraryBadge.textContent = libraryState === "owned" ? "In Sammlung" : "Auf Wunschliste";
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
                credentials: "include",
            });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            
            const data = await res.json();
            const url = typeof data?.url === "string" ? data.url : null;
            if (url) {
                markDiscogsResult(key, index, url, state, (delay) => scheduleLibraryRefresh(delay, state));
                manualSearching = false;
                window.open(url, "_blank", "noopener");
                return;
            }
        } catch (error) {
            console.warn("Discogs-Suche fehlgeschlagen:", error);
        } finally {
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
                credentials: "include",
            });
            if (!res.ok) {
                throw new Error(`HTTP ${res.status}`);
            }
            scheduleLibraryRefresh(150, state);
        } catch (e) {
            alert("Konnte nicht zur Wunschliste hinzufügen: " + (e instanceof Error ? e.message : String(e)));
        } finally {
            wishlistBtn.setAttribute("aria-disabled", track.discogsAlbumUrl ? "false" : "true");
            wishlistBtn.classList.toggle("inactive", !track.discogsAlbumUrl);
        }
    });
    
    trackDiv.appendChild(img);
    trackDiv.appendChild(infoDiv);
    trackDiv.appendChild(actions);
    
    if (initialState === "found" && track.discogsAlbumUrl) {
        setDiscogsState("found", track.discogsAlbumUrl);
    } else if (initialState === "not-found") {
        setDiscogsState("not-found");
    } else {
        setDiscogsState("pending");
    }
    
    return trackDiv;
}

export { PLACEHOLDER_IMG };
