# External API Usage Audit (Spotify + Discogs)

Date: 2026-02-12

Goal: verify VinylMatch uses external APIs efficiently (avoid request storms/spam), and capture hotspots + mitigations.

## Spotify (Web API)

### Where We Call Spotify

- `GET /api/playlist`
  - Spotify calls per request (typical, paged):
    - 1x `getPlaylist(...)` (metadata)
    - 1x `getPlaylistsItems(..., offset, limit<=100)`
    - ~1x `getSeveralAlbums([...])` per 20 tracks (unique album ids; 20 per call)
  - Mitigation: `PlaylistCache` caches the full response for `(playlistId, userSignature, offset, limit)` for 5 minutes and also snapshots to disk.

- `GET /api/user/playlists`
  - 1x `getListOfCurrentUsersPlaylists(..., offset, limit<=50)`
  - No server cache today; endpoint is protected by auth and inbound rate limiting.

### Current Efficiency Safeguards

- Paging: the playlist frontend always calls `/api/playlist` with `limit` (default page size 20) and uses `nextOffset`.
- Prefetch: playlist page prefetches one hidden page ahead (one extra `/api/playlist` call), then consumes it on "Load more".
- Caching: server caches each chunk response for 5 minutes (`cache/playlists` + memory).
- Inbound rate limiting: `RateLimitingFilter` limits requests per `clientIp|path` (defaults `RATE_LIMIT_PER_MINUTE=240`).

### Changes Made In This Audit

- Safety default: if `/api/playlist` is called without `limit` (or with `limit<=0`), it now defaults to `limit=50` instead of fetching the full playlist in one request.
  - File: `src/main/java/Server/routes/PlaylistRoutes.java`

## Discogs API

### Where We Call Discogs

- Search/matching:
  - `GET https://api.discogs.com/database/search`
  - Triggered via VinylMatch `POST /api/discogs/batch` (queue, batch size 3) and `POST /api/discogs/search` (manual per-track).

- Identity / auth validation:
  - `GET https://api.discogs.com/oauth/identity` (validate manual token and post-OAuth verification)

- Wantlist:
  - `GET  /users/{username}/wants` (wishlist panel + flags)
  - `POST /users/{username}/wants` (add)

- Collection (best-effort flags):
  - `GET /users/{username}/collection/folders/0/releases` (page 1 only; `per_page` up to 100)

### Current Efficiency Safeguards

- Matching queue (frontend):
  - Batches of 3 tracks; delays between batches.
  - Uses a faster delay only when the server reports all results were cache hits (no Discogs outbound calls).
  - Dedupes lookups by `(artist|album|year|barcode)` so repeated albums map to one lookup.

- Server caching:
  - `DiscogsCacheStore` persists matches to `cache/discogs/albums.json` and reuses them.
  - Curated links are stored (Redis-backed) and override API lookups.

- Transient failure behavior:
  - Search calls treat `429` and `5xx` as transient and retry with backoff.

### Hotspot Found

`POST /api/discogs/library-status` was expensive because it fetched:
- 1x wantlist IDs (page 1)
- 1x collection IDs (page 1)
on every call, and the UI triggers this on load, after login, after matches arrive, and on focus.

### Changes Made In This Audit

- Added a short TTL cache (20s) inside `DiscogsApiClient` for:
  - wantlist release IDs (page 1)
  - collection release IDs (page 1)
- On transient Discogs failures (`429`/`5xx`) the last cached snapshot is returned instead of dropping to empty.
- Wantlist cache is invalidated after successful wantlist add, so flags can refresh quickly.
  - File: `src/main/java/com/hctamlyniv/discogs/DiscogsApiClient.java`

## Notes / Remaining Risks

- Library flags are still best-effort for large libraries:
  - wantlist + collection ID lists only fetch page 1 (up to 100) today.
  - If we need accurate flags for large libraries, implement paging with backoff and a server-side TTL cache.

- `mvn test` currently fails due to `Server.ApiServerIntegrationTest` errors unrelated to these changes (runtime `java.lang.Error: Unresolved compilation problems` referencing `ApiErrorResponse`).
  - `mvn -DskipTests compile` succeeded.
  - Targeted test `mvn -Dtest=DiscogsApiClientTest -Djacoco.skip=true test` succeeded.

