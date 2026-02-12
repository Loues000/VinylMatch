/**
 * View Mode Module
 * Handles list/grid view toggle
 */

const VIEW_MODE_KEY = "vm:playlistViewMode";
const DEFAULT_VIEW_MODE = "list";

export function getStoredViewMode() {
    try {
        const stored = localStorage.getItem(VIEW_MODE_KEY);
        return stored === "grid" ? "grid" : DEFAULT_VIEW_MODE;
    } catch (_a) {
        return DEFAULT_VIEW_MODE;
    }
}

export function storeViewMode(mode) {
    try {
        localStorage.setItem(VIEW_MODE_KEY, mode);
    } catch (_a) {
        /* ignore */
    }
}

export function applyViewMode(mode, state, renderTracks, options = {}) {
    const normalized = mode === "grid" ? "grid" : DEFAULT_VIEW_MODE;
    state.viewMode = normalized;
    storeViewMode(normalized);
    
    const container = document.getElementById("playlist");
    if (container) {
        container.dataset.viewMode = normalized;
    }
    
    const buttons = document.querySelectorAll("#view-toggle .view-toggle-btn");
    buttons.forEach((btn) => {
        const btnMode = btn.dataset.mode === "grid" ? "grid" : "list";
        const isActive = btnMode === normalized;
        btn.classList.toggle("active", isActive);
        btn.setAttribute("aria-pressed", String(isActive));
    });
    
    if (options?.rerender && state.aggregated) {
        renderTracks(state.aggregated, { reset: true });
    }
}

export function initViewToggle(state, renderTracks) {
    const toggle = document.getElementById("view-toggle");
    if (!toggle || toggle.dataset.bound === "true") return;
    
    toggle.dataset.bound = "true";
    toggle.addEventListener("click", (event) => {
        const target = event.target instanceof HTMLElement
            ? event.target.closest(".view-toggle-btn")
            : null;
        if (!target) return;
        
        const mode = target.dataset.mode === "grid" ? "grid" : "list";
        applyViewMode(mode, state, renderTracks, { rerender: true });
        try {
            window.dispatchEvent(new CustomEvent("vm:playlist-status", {
                detail: { message: `View mode: ${mode}.` }
            }));
        }
        catch (_a) {
        }
    });
    
    applyViewMode(state.viewMode, state, renderTracks, { rerender: false });
}

export { DEFAULT_VIEW_MODE };
