const DEFAULT_TEMPLATE = "/curation.html";

const curationState = {
    queue: [],
    index: 0,
    candidates: [],
    loading: false,
    saving: false,
};

function safeDiscogsUrl(url) {
    try {
        const parsed = new URL(url, window.location.origin);
        const host = parsed.hostname.toLowerCase();
        if ((parsed.protocol === "https:" || parsed.protocol === "http:") && host.endsWith("discogs.com")) {
            return parsed.href;
        }
        return null;
    }
    catch (_a) {
        return null;
    }
}

function safeDiscogsImage(url) {
    return safeDiscogsUrl(url);
}

function select(container, selector) {
    return container?.querySelector(selector);
}

async function injectTemplate(container, templateUrl) {
    if (!container || !templateUrl)
        return;
    try {
        const res = await fetch(templateUrl);
        if (!res.ok) {
            throw new Error(`HTTP ${res.status}`);
        }
        container.innerHTML = await res.text();
    }
    catch (error) {
        console.warn("Konnte Curation-Template nicht laden", error);
        container.innerHTML = "<p class=\"muted\">Curation-Panel konnte nicht geladen werden.</p>";
    }
}

function resetCurationUi(container, message) {
    const album = select(container, "#curation-album");
    const grid = select(container, "#curation-candidates");
    const empty = select(container, "#curation-empty");
    if (album)
        album.textContent = "";
    if (grid)
        grid.textContent = "";
    if (empty) {
        if (message)
            empty.textContent = message;
        empty.classList.remove("hidden");
    }
}

function updateCurationProgress(container) {
    const progress = select(container, "#curation-progress");
    if (!progress)
        return;
    if (!curationState.queue.length) {
        progress.textContent = "0 / 0";
        return;
    }
    progress.textContent = `${curationState.index + 1} / ${curationState.queue.length}`;
}

function renderCurationAlbum(container, item, placeholderImage) {
    const album = select(container, "#curation-album");
    const empty = select(container, "#curation-empty");
    if (!album)
        return;
    album.textContent = "";
    if (empty)
        empty.classList.add("hidden");
    if (!item) {
        resetCurationUi(container, "Keine Playlist-Titel geladen.");
        return;
    }
    const img = document.createElement("img");
    img.className = "thumb";
    img.src = item.coverUrl || placeholderImage;
    img.alt = item.album || "Album Cover";
    const meta = document.createElement("div");
    meta.className = "meta";
    const title = document.createElement("div");
    title.className = "title";
    title.textContent = item.album || "Unbekanntes Album";
    const artist = document.createElement("div");
    artist.className = "artist";
    artist.textContent = item.artist || "Unbekannter Artist";
    const hint = document.createElement("div");
    hint.className = "hint";
    hint.textContent = item.trackName ? `aus: ${item.trackName}` : "Playlist-Album";
    meta.appendChild(title);
    meta.appendChild(artist);
    meta.appendChild(hint);
    album.appendChild(img);
    album.appendChild(meta);
}

function createCandidateCard(container, item, candidate, onCandidateSaved) {
    const safeUrl = safeDiscogsUrl(candidate.url);
    const safeThumb = safeDiscogsImage(candidate.thumb);
    if (!safeUrl) {
        return null;
    }
    const card = document.createElement("div");
    card.className = "candidate-card";
    const bar = document.createElement("div");
    bar.className = "candidate-browser";
    const urlLabel = document.createElement("div");
    urlLabel.className = "url";
    urlLabel.textContent = safeUrl || "ohne URL";
    const openLink = document.createElement("a");
    openLink.href = safeUrl || "#";
    openLink.target = "_blank";
    openLink.rel = "noopener noreferrer";
    openLink.textContent = "Öffnen";
    bar.appendChild(urlLabel);
    bar.appendChild(openLink);
    const preview = document.createElement("div");
    preview.className = "candidate-preview";
    if (safeThumb) {
        const img = document.createElement("img");
        img.src = safeThumb;
        img.alt = candidate.title || "Discogs Vorschau";
        preview.appendChild(img);
    }
    else {
        const placeholder = document.createElement("div");
        placeholder.className = "placeholder";
        placeholder.textContent = "Kein Vorschaubild";
        preview.appendChild(placeholder);
    }
    const meta = document.createElement("div");
    meta.className = "candidate-meta";
    const title = document.createElement("div");
    title.className = "title";
    title.textContent = candidate.title || "Ohne Titel";
    const details = document.createElement("div");
    details.className = "details";
    const detailParts = [];
    if (candidate.artist)
        detailParts.push(candidate.artist);
    if (candidate.year)
        detailParts.push(candidate.year);
    if (candidate.country)
        detailParts.push(candidate.country);
    if (candidate.format)
        detailParts.push(candidate.format);
    details.textContent = detailParts.join(" • ") || "Discogs-Ergebnis";
    meta.appendChild(title);
    meta.appendChild(details);
    const actions = document.createElement("div");
    actions.className = "candidate-actions";
    const selectButton = document.createElement("button");
    selectButton.type = "button";
    selectButton.className = "btn";
    selectButton.textContent = "Als korrekt sichern";
    selectButton.addEventListener("click", () => selectCandidate(container, item, candidate, selectButton, onCandidateSaved));
    actions.appendChild(selectButton);
    card.appendChild(bar);
    card.appendChild(preview);
    card.appendChild(meta);
    card.appendChild(actions);
    return card;
}

function renderCurationCandidates(container, item, candidates, onCandidateSaved) {
    const grid = select(container, "#curation-candidates");
    const empty = select(container, "#curation-empty");
    if (!grid)
        return;
    grid.textContent = "";
    if (!Array.isArray(candidates) || !candidates.length) {
        if (empty) {
            empty.textContent = "Keine Kandidaten gefunden. Versuch es mit dem nächsten Album.";
            empty.classList.remove("hidden");
        }
        return;
    }
    if (empty)
        empty.classList.add("hidden");
    for (const candidate of candidates) {
        const card = createCandidateCard(container, item, candidate, onCandidateSaved);
        if (card) {
            grid.appendChild(card);
        }
    }
}

async function loadCurationCandidates(container, item) {
    if (!item)
        return [];
    curationState.loading = true;
    const empty = select(container, "#curation-empty");
    if (empty) {
        empty.textContent = "Discogs-Kandidaten werden geladen …";
        empty.classList.remove("hidden");
    }
    try {
        const res = await fetch("/api/discogs/curation/candidates", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                artist: item.artist,
                album: item.album,
                year: item.releaseYear,
                trackTitle: item.trackName,
            }),
        });
        if (!res.ok)
            throw new Error("HTTP " + res.status);
        const payload = await res.json();
        return Array.isArray(payload?.candidates) ? payload.candidates : [];
    }
    catch (e) {
        console.warn("Konnte Curation-Kandidaten nicht laden", e);
        resetCurationUi(container, "Fehler beim Laden der Kandidaten.");
        return [];
    }
    finally {
        curationState.loading = false;
    }
}

async function selectCandidate(container, item, candidate, button, onCandidateSaved) {
    const safeUrl = safeDiscogsUrl(candidate?.url);
    if (!safeUrl || curationState.saving)
        return;
    curationState.saving = true;
    const original = button.textContent;
    button.textContent = "Speichert …";
    button.disabled = true;
    try {
        const res = await fetch("/api/discogs/curation/save", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                artist: item.artist,
                album: item.album,
                year: item.releaseYear,
                trackTitle: item.trackName,
                url: safeUrl,
                thumb: safeDiscogsImage(candidate.thumb),
            }),
        });
        if (!res.ok)
            throw new Error("HTTP " + res.status);
        button.textContent = "Gespeichert ✔";
        onCandidateSaved?.(item, candidate.url);
        setTimeout(() => (button.textContent = original), 1200);
    }
    catch (e) {
        alert("Konnte Link nicht speichern: " + (e instanceof Error ? e.message : String(e)));
        button.textContent = original;
    }
    finally {
        button.disabled = false;
        curationState.saving = false;
    }
}

async function showCurationItem(container, step, placeholderImage, onCandidateSaved) {
    if (!curationState.queue.length) {
        resetCurationUi(container, "Keine Playlist geladen.");
        updateCurationProgress(container);
        return;
    }
    curationState.index = Math.min(Math.max(0, curationState.index + step), curationState.queue.length - 1);
    const current = curationState.queue[curationState.index];
    renderCurationAlbum(container, current, placeholderImage);
    updateCurationProgress(container);
    curationState.candidates = await loadCurationCandidates(container, current);
    renderCurationCandidates(container, current, curationState.candidates, onCandidateSaved);
}

async function initCurationPanel(options = {}) {
    const container = options.container ?? document.getElementById(options.containerId ?? "curation-panel-container");
    if (!container)
        return null;
    const templatePath = options.templateUrl ?? container.dataset.template ?? DEFAULT_TEMPLATE;
    if (templatePath) {
        await injectTemplate(container, templatePath);
    }
    resetCurationUi(container, "Noch keine Playlist geladen.");
    updateCurationProgress(container);
    const placeholderImage = options.placeholderImage ?? "";
    const buildQueue = typeof options.buildQueue === "function" ? options.buildQueue : () => [];
    const onCandidateSaved = typeof options.onCandidateSaved === "function" ? options.onCandidateSaved : () => {};
    const startBtn = select(container, "#curation-start");
    const nextBtn = select(container, "#curation-next");
    const prevBtn = select(container, "#curation-prev");
    startBtn?.addEventListener("click", () => {
        curationState.queue = buildQueue();
        curationState.index = 0;
        if (!curationState.queue.length) {
            resetCurationUi(container, "Keine Playlist-Tracks gefunden.");
            updateCurationProgress(container);
            return;
        }
        showCurationItem(container, 0, placeholderImage, onCandidateSaved);
    });
    nextBtn?.addEventListener("click", () => showCurationItem(container, 1, placeholderImage, onCandidateSaved));
    prevBtn?.addEventListener("click", () => showCurationItem(container, -1, placeholderImage, onCandidateSaved));
    return {
        refreshQueue: () => {
            curationState.queue = buildQueue();
            curationState.index = Math.min(curationState.index, Math.max(0, curationState.queue.length - 1));
            updateCurationProgress(container);
        },
    };
}

export { initCurationPanel };
