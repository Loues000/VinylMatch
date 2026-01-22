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
const ICON_DISCOGS = `<svg width="100px" height="100px" viewBox="-12.8 -12.8 89.60 89.60" version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:sketch="http://www.bohemiancoding.com/sketch/ns" fill="#ffffff" stroke="#ffffff"><g id="SVGRepo_bgCarrier" stroke-width="0"></g><g id="SVGRepo_tracerCarrier" stroke-linecap="round" stroke-linejoin="round"></g><g id="SVGRepo_iconCarrier"> <title>Vinyl</title> <desc>Created with Sketch.</desc> <defs> </defs> <g id="Page-1" stroke-width="1.28" fill="none" fill-rule="evenodd" sketch:type="MSPage"> <g id="Vinyl" sketch:type="MSArtboardGroup" fill="#FFF"> <path d="M24.9893,54.4355 C17.6283,52.1385 11.8613,46.3725 9.5643,39.0105 C9.4823,38.7475 9.2063,38.5995 8.9373,38.6825 C8.6753,38.7655 8.5273,39.0455 8.6093,39.3095 C11.0053,46.9835 17.0163,52.9955 24.6903,55.3905 C24.7403,55.4065 24.7913,55.4135 24.8403,55.4135 C25.0523,55.4135 25.2503,55.2765 25.3173,55.0625 C25.3993,54.7985 25.2523,54.5185 24.9893,54.4355 M25.8853,51.5715 C19.4633,49.5675 14.4323,44.5375 12.4283,38.1155 C12.3463,37.8525 12.0683,37.7045 11.8013,37.7875 C11.5383,37.8705 11.3913,38.1505 11.4733,38.4145 C13.5763,45.1485 18.8513,50.4235 25.5863,52.5265 C25.6353,52.5415 25.6863,52.5485 25.7353,52.5485 C25.9483,52.5485 26.1453,52.4125 26.2133,52.1985 C26.2953,51.9345 26.1483,51.6545 25.8853,51.5715 M24.0933,57.2995 C15.7943,54.7085 9.2913,48.2065 6.7003,39.9065 C6.6173,39.6435 6.3433,39.4945 6.0733,39.5785 C5.8093,39.6615 5.6633,39.9415 5.7453,40.2055 C8.4343,48.8175 15.1823,55.5655 23.7953,58.2545 C23.8443,58.2705 23.8953,58.2775 23.9443,58.2775 C24.1573,58.2775 24.3543,58.1405 24.4223,57.9265 C24.5043,57.6635 24.3573,57.3825 24.0933,57.2995 M39.3093,8.6095 C39.0433,8.5265 38.7653,8.6755 38.6823,8.9375 C38.6003,9.2015 38.7483,9.4815 39.0103,9.5635 C46.3723,11.8615 52.1383,17.6275 54.4353,24.9885 C54.5033,25.2025 54.7003,25.3395 54.9133,25.3395 C54.9623,25.3395 55.0123,25.3325 55.0623,25.3165 C55.3253,25.2345 55.4723,24.9545 55.3903,24.6905 C52.9953,17.0165 46.9833,11.0045 39.3093,8.6095 M38.4153,11.4745 C38.1473,11.3895 37.8713,11.5395 37.7883,11.8025 C37.7063,12.0665 37.8533,12.3465 38.1163,12.4285 C44.5373,14.4335 49.5663,19.4625 51.5713,25.8835 C51.6383,26.0975 51.8363,26.2345 52.0493,26.2345 C52.0973,26.2345 52.1483,26.2275 52.1983,26.2115 C52.4623,26.1295 52.6083,25.8495 52.5263,25.5855 C50.4243,18.8515 45.1483,13.5765 38.4153,11.4745 M58.2553,23.7955 C55.5653,15.1825 48.8183,8.4345 40.2053,5.7455 C39.9373,5.6605 39.6613,5.8105 39.5783,6.0735 C39.4963,6.3375 39.6423,6.6175 39.9063,6.6995 C48.2073,9.2915 54.7093,15.7935 57.3003,24.0935 C57.3673,24.3075 57.5643,24.4445 57.7773,24.4445 C57.8263,24.4445 57.8773,24.4375 57.9273,24.4215 C58.1903,24.3395 58.3373,24.0595 58.2553,23.7955 M32.0003,32.9995 C31.4483,32.9995 31.0003,32.5515 31.0003,31.9995 C31.0003,31.4485 31.4483,30.9995 32.0003,30.9995 C32.5513,30.9995 33.0003,31.4485 33.0003,31.9995 C33.0003,32.5515 32.5513,32.9995 32.0003,32.9995 M32.0003,29.9995 C30.8953,29.9995 30.0003,30.8955 30.0003,31.9995 C30.0003,33.1045 30.8953,33.9995 32.0003,33.9995 C33.1043,33.9995 34.0003,33.1045 34.0003,31.9995 C34.0003,30.8955 33.1043,29.9995 32.0003,29.9995 M32.0003,40.9995 C27.0373,40.9995 23.0003,36.9625 23.0003,31.9995 C23.0003,27.0375 27.0373,22.9995 32.0003,22.9995 C36.9623,22.9995 41.0003,27.0375 41.0003,31.9995 C41.0003,36.9625 36.9623,40.9995 32.0003,40.9995 M32.0003,20.9995 C25.9253,20.9995 21.0003,25.9245 21.0003,31.9995 C21.0003,38.0755 25.9253,42.9995 32.0003,42.9995 C38.0753,42.9995 43.0003,38.0755 43.0003,31.9995 C43.0003,25.9245 38.0753,20.9995 32.0003,20.9995 M32.0003,61.9995 C15.4583,61.9995 2.0003,48.5425 2.0003,31.9995 C2.0003,15.4575 15.4583,1.9995 32.0003,1.9995 C48.5423,1.9995 62.0003,15.4575 62.0003,31.9995 C62.0003,48.5425 48.5423,61.9995 32.0003,61.9995 M32.0003,-0.0005 C14.3273,-0.0005 0.0003,14.3265 0.0003,31.9995 C0.0003,49.6735 14.3273,63.9995 32.0003,63.9995 C49.6733,63.9995 64.0003,49.6735 64.0003,31.9995 C64.0003,14.3265 49.6733,-0.0005 32.0003,-0.0005" sketch:type="MSShapeGroup"> </path> </g> </g> </g></svg>`;
const ICON_WANT = `<svg width="100px" height="100px" viewBox="-2.4 -2.4 28.80 28.80" fill="none" xmlns="http://www.w3.org/2000/svg"><g id="SVGRepo_bgCarrier" stroke-width="0"></g><g id="SVGRepo_tracerCarrier" stroke-linecap="round" stroke-linejoin="round"></g><g id="SVGRepo_iconCarrier"> <path fill-rule="evenodd" clip-rule="evenodd" d="M12 6.00019C10.2006 3.90317 7.19377 3.2551 4.93923 5.17534C2.68468 7.09558 2.36727 10.3061 4.13778 12.5772C5.60984 14.4654 10.0648 18.4479 11.5249 19.7369C11.6882 19.8811 11.7699 19.9532 11.8652 19.9815C11.9483 20.0062 12.0393 20.0062 12.1225 19.9815C12.2178 19.9532 12.2994 19.8811 12.4628 19.7369C13.9229 18.4479 18.3778 14.4654 19.8499 12.5772C21.6204 10.3061 21.3417 7.07538 19.0484 5.17534C16.7551 3.2753 13.7994 3.90317 12 6.00019Z" stroke="#FFF" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"></path> </g></svg>`;

export function determineMatchQuality(url) {
    if (!url || typeof url !== "string") {
        return { level: "poor", label: "No match" };
    }
    const normalized = url.toLowerCase();
    if (normalized.includes("/release/") || normalized.includes("/master/")) {
        return { level: "good", label: "Direct match" };
    }
    if (normalized.includes("/search")) {
        return { level: "medium", label: "Search" };
    }
    return { level: "medium", label: "Found" };
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
            ? { level: "poor", label: "No match" }
            : { level: "pending", label: "Searching…" });
    
    const trackDiv = document.createElement("div");
    trackDiv.className = "track";
    if (key) trackDiv.dataset.trackKey = key;
    trackDiv.dataset.matchQuality = initialQuality.level;
    
    const img = document.createElement("img");
    img.className = "cover";
    img.src = track.coverUrl || PLACEHOLDER_IMG;
    img.alt = track.album ? `Album cover for ${track.album}` : "Album cover";
    
    const infoDiv = document.createElement("div");
    infoDiv.className = "info";
    
    const titleDiv = document.createElement("div");
    titleDiv.className = "track-title";
    titleDiv.textContent = track.trackName || "Unknown track";
    
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
    discogsBtn.href = "#";
    discogsBtn.classList.add("discogs-action");
    discogsBtn.setAttribute("aria-label", "Search on Discogs");
    discogsBtn.innerHTML = ICON_DISCOGS;
    actions.appendChild(discogsBtn);
    
    const wishlistBtn = document.createElement("a");
    wishlistBtn.href = "#";
    wishlistBtn.classList.add("discogs-action");
    wishlistBtn.setAttribute("aria-label", "Add to Discogs wantlist");
    wishlistBtn.innerHTML = ICON_WANT;
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
            discogsBtn.title = quality.level === "good" ? "Open on Discogs" : "Discogs search result";
        } else if (discogsStateVal === "pending") {
            updateBadge({ level: "pending", label: "Searching…" });
            track.discogsStatus = "pending";
            discogsBtn.href = "#";
            discogsBtn.classList.add("inactive", "pending");
            discogsBtn.setAttribute("aria-disabled", "true");
            discogsBtn.removeAttribute("target");
            discogsBtn.removeAttribute("rel");
            discogsBtn.title = "Searching Discogs…";
        } else {
            updateBadge({ level: "poor", label: "No match" });
            track.discogsAlbumUrl = null;
            track.discogsStatus = "not-found";
            discogsBtn.href = "#";
            discogsBtn.classList.remove("pending");
            discogsBtn.classList.remove("inactive");
            discogsBtn.setAttribute("aria-disabled", "false");
            discogsBtn.removeAttribute("target");
            discogsBtn.removeAttribute("rel");
            discogsBtn.title = "Search Discogs again";
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
        libraryBadge.textContent = libraryState === "owned" ? "In collection" : "In wantlist";
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
            console.warn("Discogs search failed:", error);
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
            alert("Please connect Discogs first.");
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
            alert("Could not add to wantlist: " + (e instanceof Error ? e.message : String(e)));
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
