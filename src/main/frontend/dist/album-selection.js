/**
 * Album Selection Module
 * Provides UI for selecting albums before Discogs search
 */

const PLACEHOLDER_IMG = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1 1'%3E%3C/svg%3E";

let selectedAlbums = new Set();
let albumsData = [];
let onConfirmCallback = null;

/**
 * Shows the album selection modal
 * @param {Array} albums - Array of AlbumGroup objects from API
 * @param {Function} onConfirm - Callback when user confirms selection
 */
export function showAlbumSelection(albums, onConfirm) {
    albumsData = albums || [];
    onConfirmCallback = onConfirm;
    selectedAlbums.clear();
    
    // Select all by default
    albumsData.forEach((album, index) => {
        selectedAlbums.add(index);
    });
    
    renderAlbumSelectionModal();
}

/**
 * Renders the album selection modal
 */
function renderAlbumSelectionModal() {
    // Remove existing modal if present
    const existingModal = document.getElementById('album-selection-modal');
    if (existingModal) {
        existingModal.remove();
    }
    
    const modal = document.createElement('div');
    modal.id = 'album-selection-modal';
    modal.className = 'album-selection-modal';
    modal.innerHTML = `
        <div class="album-selection-backdrop"></div>
        <div class="album-selection-content">
            <div class="album-selection-header">
                <div>
                    <h2>Select Albums</h2>
                    <p class="album-selection-subtitle">
                        ${albumsData.length} albums found with ${getTotalTrackCount()} tracks
                    </p>
                </div>
                <button type="button" class="album-selection-close" aria-label="Close">&times;</button>
            </div>
            
            <div class="album-selection-actions">
                <button type="button" class="btn ghost" id="album-select-all">Select All</button>
                <button type="button" class="btn ghost" id="album-deselect-all">Deselect All</button>
                <span class="album-selection-count">${selectedAlbums.size} selected</span>
            </div>
            
            <div class="album-selection-list">
                ${albumsData.map((album, index) => renderAlbumItem(album, index)).join('')}
            </div>
            
            <div class="album-selection-footer">
                <button type="button" class="btn primary" id="album-confirm" disabled>
                    Search Discogs for Selected Albums
                </button>
            </div>
        </div>
    `;
    
    document.body.appendChild(modal);
    document.body.classList.add('album-selection-open');
    
    // Bind events
    bindModalEvents();
    updateSelectionCount();
}

/**
 * Renders a single album item
 */
function renderAlbumItem(album, index) {
    const isSelected = selectedAlbums.has(index);
    const year = album.releaseYear ? `(${album.releaseYear})` : '';
    
    return `
        <label class="album-selection-item ${isSelected ? 'selected' : ''}" data-index="${index}">
            <input type="checkbox" ${isSelected ? 'checked' : ''} data-index="${index}">
            <img src="${album.coverUrl || PLACEHOLDER_IMG}" alt="${album.album}" class="album-cover" loading="lazy">
            <div class="album-info">
                <div class="album-title">${escapeHtml(album.album)} ${year}</div>
                <div class="album-artist">${escapeHtml(album.artist)}</div>
                <div class="album-tracks">${album.trackCount} tracks</div>
            </div>
            <div class="album-check">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="20 6 9 17 4 12"></polyline>
                </svg>
            </div>
        </label>
    `;
}

/**
 * Binds event listeners to modal elements
 */
function bindModalEvents() {
    const modal = document.getElementById('album-selection-modal');
    if (!modal) return;
    
    // Close button
    modal.querySelector('.album-selection-close')?.addEventListener('click', closeModal);
    modal.querySelector('.album-selection-backdrop')?.addEventListener('click', closeModal);
    
    // Select/Deselect all
    modal.querySelector('#album-select-all')?.addEventListener('click', () => {
        albumsData.forEach((_, index) => selectedAlbums.add(index));
        updateSelectionUI();
    });
    
    modal.querySelector('#album-deselect-all')?.addEventListener('click', () => {
        selectedAlbums.clear();
        updateSelectionUI();
    });
    
    // Individual checkboxes
    modal.querySelectorAll('.album-selection-item input[type="checkbox"]').forEach(checkbox => {
        checkbox.addEventListener('change', (e) => {
            const index = parseInt(e.target.dataset.index);
            if (e.target.checked) {
                selectedAlbums.add(index);
            } else {
                selectedAlbums.delete(index);
            }
            updateSelectionUI();
        });
    });
    
    // Click on item to toggle
    modal.querySelectorAll('.album-selection-item').forEach(item => {
        item.addEventListener('click', (e) => {
            if (e.target.tagName === 'INPUT') return;
            const checkbox = item.querySelector('input[type="checkbox"]');
            checkbox.checked = !checkbox.checked;
            checkbox.dispatchEvent(new Event('change'));
        });
    });
    
    // Confirm button
    modal.querySelector('#album-confirm')?.addEventListener('click', () => {
        if (selectedAlbums.size === 0) return;
        
        const selectedAlbumData = albumsData.filter((_, index) => selectedAlbums.has(index));
        closeModal();
        
        if (onConfirmCallback) {
            onConfirmCallback(selectedAlbumData);
        }
    });
    
    // Keyboard shortcut
    modal.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            closeModal();
        }
    });
}

/**
 * Updates UI based on current selection
 */
function updateSelectionUI() {
    const modal = document.getElementById('album-selection-modal');
    if (!modal) return;
    
    // Update checkboxes
    modal.querySelectorAll('.album-selection-item').forEach((item, index) => {
        const isSelected = selectedAlbums.has(index);
        item.classList.toggle('selected', isSelected);
        const checkbox = item.querySelector('input[type="checkbox"]');
        if (checkbox) checkbox.checked = isSelected;
    });
    
    updateSelectionCount();
}

/**
 * Updates selection count display
 */
function updateSelectionCount() {
    const modal = document.getElementById('album-selection-modal');
    if (!modal) return;
    
    const countEl = modal.querySelector('.album-selection-count');
    const confirmBtn = modal.querySelector('#album-confirm');
    
    if (countEl) {
        countEl.textContent = `${selectedAlbums.size} selected`;
    }
    
    if (confirmBtn) {
        confirmBtn.disabled = selectedAlbums.size === 0;
        confirmBtn.textContent = selectedAlbums.size === 0 
            ? 'Select at least one album'
            : `Search Discogs for ${selectedAlbums.size} Album${selectedAlbums.size !== 1 ? 's' : ''}`;
    }
}

/**
 * Closes the modal
 */
function closeModal() {
    const modal = document.getElementById('album-selection-modal');
    if (modal) {
        modal.remove();
    }
    document.body.classList.remove('album-selection-open');
}

/**
 * Gets total track count across all albums
 */
function getTotalTrackCount() {
    return albumsData.reduce((sum, album) => sum + album.trackCount, 0);
}

/**
 * Escapes HTML to prevent XSS
 */
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * Returns selected album indices for filtering tracks
 */
export function getSelectedAlbumIndices() {
    return new Set(selectedAlbums);
}

/**
 * Checks if an album is selected
 */
export function isAlbumSelected(albumIndex) {
    return selectedAlbums.has(albumIndex);
}
