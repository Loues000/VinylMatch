/**
 * Track Renderer Module
 * Creates track elements and handles quality badges
 */

import { primaryArtist, normalizeForSearch } from "../common/playlist-utils.js";
import { getVendors, buildVendorUrl } from "../common/vendors.js";
import { buildTrackKey, registerTrackElement } from "./track-registry.js";
import { safeDiscogsUrl, markDiscogsResult, discogsState } from "./discogs-state.js";
import { discogsUiState, rememberLibraryState, scheduleLibraryRefresh } from "./discogs-ui.js";

function emitPlaylistStatus(message, tone = "neutral") {
    try {
        window.dispatchEvent(new CustomEvent("vm:playlist-status", { detail: { message, tone } }));
    }
    catch (_a) {
    }
}

async function checkWantlistMembership(targetUrl) {
    const safeUrl = safeDiscogsUrl(targetUrl);
    if (!safeUrl) {
        return null;
    }
    try {
        const res = await fetch("/api/discogs/library-status", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ urls: [safeUrl] }),
            credentials: "include",
        });
        if (!res.ok) {
            return null;
        }
        const payload = await res.json();
        const results = Array.isArray(payload?.results) ? payload.results : [];
        const result = results.find((item) => {
            const entryUrl = typeof item?.url === "string" ? safeDiscogsUrl(item.url) : null;
            return entryUrl === safeUrl;
        });
        if (!result) {
            return false;
        }
        return !!result.inWishlist || !!result.inCollection;
    } catch (_a) {
        return null;
    }
}

const PLACEHOLDER_IMG = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";
const ICON_DISCOGS = `<img class="vm-action-icon vm-action-icon--light" src="/design/discogs_trans_black.svg" alt="" aria-hidden="true"><img class="vm-action-icon vm-action-icon--dark" src="/design/discogs_trans_white.svg" alt="" aria-hidden="true">`;
const ICON_WANT = `<img class="vm-action-icon vm-action-icon--light" src="/design/heart_trans_black.svg" alt="" aria-hidden="true"><img class="vm-action-icon vm-action-icon--dark" src="/design/heart_trans_white.svg" alt="" aria-hidden="true">`;
const DEFAULT_VENDOR_ICONS = {
    hhv: { light: "/design/hhv_trans_black.svg", dark: "/design/hhv_trans_white.svg" },
    jpc: { light: "/design/jpc_trans_black.svg", dark: "/design/jpc_trans_white.svg" },
    amazon: { light: "/design/amazon_trans_black.svg", dark: "/design/amazon_trans_white.svg" },
};

function resolveVendorIcons(vendor) {
    if (!vendor) return null;
    const fallback = DEFAULT_VENDOR_ICONS[vendor.id] || null;
    const light = typeof vendor.iconLight === "string"
        ? vendor.iconLight
        : (typeof vendor?.icon?.light === "string"
            ? vendor.icon.light
            : fallback?.light);
    const dark = typeof vendor.iconDark === "string"
        ? vendor.iconDark
        : (typeof vendor?.icon?.dark === "string"
            ? vendor.icon.dark
            : fallback?.dark);
    if (!light || !dark) return null;
    return { light, dark };
}

function appendVendorIcons(link, vendor, fallback) {
    const icons = resolveVendorIcons(vendor);
    if (!icons || !fallback) return;
    const light = document.createElement("img");
    light.className = "vm-action-icon vm-action-icon--light";
    light.src = icons.light;
    light.alt = "";
    light.setAttribute("aria-hidden", "true");
    const dark = document.createElement("img");
    dark.className = "vm-action-icon vm-action-icon--dark";
    dark.src = icons.dark;
    dark.alt = "";
    dark.setAttribute("aria-hidden", "true");
    const fallbackToLetter = () => {
        link.classList.remove("vendor-link--has-icon");
        light.remove();
        dark.remove();
    };
    light.addEventListener("error", fallbackToLetter, { once: true });
    dark.addEventListener("error", fallbackToLetter, { once: true });
    link.classList.add("vendor-link--has-icon");
    link.appendChild(light);
    link.appendChild(dark);
}

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
        const vendorName = vendor.name || vendor.id || "Vendor";
        const fallbackLabel = document.createElement("span");
        fallbackLabel.className = "vm-action-fallback";
        fallbackLabel.textContent = vendor.label || vendor.id?.charAt(0)?.toUpperCase() || "?";
        b.appendChild(fallbackLabel);
        appendVendorIcons(b, vendor, fallbackLabel);
        b.title = vendorName;
        b.setAttribute("aria-label", vendorName);
        b.classList.add("vendor-link");
        if (vendor.id) {
            b.classList.add(`vendor-link--${String(vendor.id).toLowerCase()}`);
        }
        
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
    let currentLibraryState = null;

    const syncWishlistActionState = () => {
        const alreadyTracked = currentLibraryState === "wishlist" || currentLibraryState === "owned";
        const canAdd = !!track.discogsAlbumUrl
            && track.discogsStatus === "found"
            && discogsUiState.loggedIn
            && !alreadyTracked;
        wishlistBtn.setAttribute("aria-disabled", canAdd ? "false" : "true");
        wishlistBtn.classList.toggle("inactive", !canAdd);
        if (!discogsUiState.loggedIn) {
            wishlistBtn.title = "Connect Discogs first";
        } else if (alreadyTracked) {
            wishlistBtn.title = currentLibraryState === "owned" ? "Already in collection" : "Already in wantlist";
        } else {
            wishlistBtn.title = canAdd ? "Add to Discogs wantlist" : "Discogs match required";
        }
    };
    
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
        
        syncWishlistActionState();
    };
    
    const setLibraryState = (libraryState) => {
        currentLibraryState = libraryState || null;
        if (!libraryState) {
            libraryBadge.className = "discogs-library hidden";
            libraryBadge.textContent = "";
            syncWishlistActionState();
            return;
        }
        libraryBadge.className = `discogs-library ${libraryState}`;
        libraryBadge.textContent = libraryState === "owned" ? "In collection" : "In wantlist";
        syncWishlistActionState();
    };
    
    registerTrackElement(key, { index, setDiscogsState, setLibraryState, element: trackDiv });
    setLibraryState(null);
    const onDiscogsAuthState = () => {
        syncWishlistActionState();
        if (!trackDiv.isConnected) {
            window.removeEventListener("vm:discogs-auth-state", onDiscogsAuthState);
        }
    };
    window.addEventListener("vm:discogs-auth-state", onDiscogsAuthState);
    
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
            emitPlaylistStatus("Please connect Discogs first.", "error");
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
            if (res.status === 409) {
                const membership = await checkWantlistMembership(targetUrl);
                if (membership === true) {
                    rememberLibraryState(track.artist, track.album, "wishlist");
                    setLibraryState("wishlist");
                    scheduleLibraryRefresh(150, state);
                    emitPlaylistStatus("Already in wantlist.", "neutral");
                    return;
                }
                if (membership === false) {
                    throw new Error("Conflict: release is not in your wantlist.");
                }
                throw new Error("Conflict while adding to wantlist. Please refresh Discogs status.");
            }
            if (!res.ok) {
                let message = `HTTP ${res.status}`;
                try {
                    const payload = await res.json();
                    const apiMessage = payload?.error?.message;
                    if (typeof apiMessage === "string" && apiMessage.trim()) {
                        message = apiMessage.trim();
                    }
                } catch (_) {
                }
                throw new Error(message);
            }
            rememberLibraryState(track.artist, track.album, "wishlist");
            setLibraryState("wishlist");
            scheduleLibraryRefresh(150, state);
        } catch (e) {
            emitPlaylistStatus("Could not add to wantlist: " + (e instanceof Error ? e.message : String(e)), "error");
        } finally {
            syncWishlistActionState();
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
