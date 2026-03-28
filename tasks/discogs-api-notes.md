# Discogs API Notes (VinylMatch)

These notes are a project-focused reference for how VinylMatch uses the Discogs HTTP API for matching Spotify tracks to Discogs releases (vinyl).

Source note: the official Discogs Developers portal (`https://www.discogs.com/developers/`) returned `403` from this environment on 2026-02-12, so this doc is assembled from:
- The current VinylMatch implementation (authoritative for what we actually call today).
- Public client-library references that mirror the Discogs docs/headers/limits.

Validate against the official docs when you have direct access.

## TL;DR For VinylMatch

- Use server-side calls only. Browsers cannot reliably set `User-Agent`, and Discogs requires it.
- Authenticate with either:
  - A personal access token (`Authorization: Discogs token=...`) for simple user-scoped calls, or
  - OAuth 1.0a (request token -> authorize -> access token) when we want an in-app connect flow.
- Our primary match endpoint is `GET https://api.discogs.com/database/search` and we store *Discogs web URLs* (not API `resource_url`s) like `https://www.discogs.com/release/{id}` or `https://www.discogs.com/master/{id}`.
- Treat `429` and `5xx` as transient. Retry with backoff and do not permanently cache low-quality fallback URLs from transient failures.

## Base URLs

- API base: `https://api.discogs.com`
- Web base (user-facing): `https://www.discogs.com`

VinylMatch normalizes to Discogs *web URLs* for display, actions, and caching.

## Required Headers

Discogs expects:
- `User-Agent`: identifies your application (required).
- `Accept: application/json`: current VinylMatch uses JSON responses.

Auth header options used by VinylMatch:
- Personal access token:
  - `Authorization: Discogs token=YOUR_TOKEN`
- OAuth 1.0a:
  - `Authorization: OAuth oauth_consumer_key="...", oauth_token="...", oauth_signature="...", ...`

Implementation reference:
- `src/main/java/com/hctamlyniv/discogs/DiscogsApiClient.java`

## Rate Limiting

Common behavior (as reflected by multiple clients and examples):
- Unauthenticated: about `25` requests/minute
- Authenticated: about `60` requests/minute
- Rate limit failures return `429 Too Many Requests`

Headers you can use for adaptive throttling:
- `X-Discogs-Ratelimit`
- `X-Discogs-Ratelimit-Used`
- `X-Discogs-Ratelimit-Remaining`

VinylMatch behavior today:
- Detects transient statuses for searches (`429` and `5xx`) and retries with backoff at the service layer.
- Avoids caching fallback search URLs on transient failure.

Implementation references:
- `src/main/java/com/hctamlyniv/discogs/DiscogsApiClient.java`
- `src/main/java/com/hctamlyniv/DiscogsService.java`
- `src/main/java/Server/auth/DiscogsOAuthService.java` (request-token retry)

## Pagination

Common patterns:
- Requests: `page` (1-based) and `per_page` (often up to `100` depending on endpoint)
- Responses: `pagination` object with counts and navigation URLs

VinylMatch examples:
- Wantlist: uses `page` and `per_page`, reads `pagination.items` as total.
- Collection folder: currently fetches `page=1` only with `per_page` up to `100`.

Implementation references:
- `src/main/java/com/hctamlyniv/discogs/DiscogsApiClient.java`
- `src/main/java/Server/routes/DiscogsRoutes.java`

## Discogs API Endpoints Used By VinylMatch

### Identity (Who Am I?)

Used to validate a token / complete OAuth connect.

- `GET /oauth/identity`

Implementation:
- `src/main/java/com/hctamlyniv/discogs/DiscogsApiClient.java` (`fetchProfile`)

### Database Search (Core Matching)

Primary lookup endpoint for mapping (artist, album, year, track) to a Discogs result.

- `GET /database/search`

Query params VinylMatch uses today:
- `q`: free-text query (used for fast, broad matching)
- `type`: `release` or `master`
- `artist`: artist name
- `release_title`: album title
- `track`: track title (helps disambiguate)
- `year`: optional release year
- `barcode`: optional barcode identifier
- `per_page`: typically `5` or `10` in our implementation
- `sort`: `relevance`

Response fields VinylMatch depends on:
- `results[].type`: `release` or `master`
- `results[].uri`: a Discogs web path like `/release/123` or `/master/456`
- `results[].id`, `results[].title`, `results[].year`, `results[].country`
- `results[].format` (array, may be present)
- `results[].thumb` (optional)

Important: `results[].uri` is a *web path*. VinylMatch constructs a full URL via:
- `https://www.discogs.com` + `uri`

Implementation:
- `src/main/java/com/hctamlyniv/discogs/DiscogsApiClient.java` (`searchOnce`, `searchOnceQ`, `searchByBarcode`, `fetchCurationCandidates`)
- `src/main/java/com/hctamlyniv/DiscogsService.java` (multi-pass strategy + caching rules)

### Master Lookup (Resolve Master -> Main Release)

When a match is a master, VinylMatch attempts to resolve it to a concrete release for wantlist/collection actions.

- `GET /masters/{master_id}`

Field used:
- `main_release`

Implementation:
- `src/main/java/com/hctamlyniv/discogs/DiscogsApiClient.java` (`fetchMainReleaseId`)
- `src/main/java/com/hctamlyniv/DiscogsService.java` (`resolveReleaseIdFromUrl`)

### Wantlist (Wishlist) Read

Used for:
- Showing the user wantlist in the playlist UI
- Checking `inWishlist` for matched releases

- `GET /users/{username}/wants`

Query params VinylMatch uses:
- `page`
- `per_page`
- `sort=added`
- `sort_order=desc`

Response shape VinylMatch depends on:
- `pagination.items` (total count)
- `wants[]` entries with `basic_information`
- `basic_information.id`, `basic_information.title`, `basic_information.year`
- `basic_information.artists[0].name`
- `basic_information.thumb`
- `basic_information.uri` and/or `basic_information.resource_url`

URL normalization:
- Prefer `basic_information.uri` (web path) when present
- Fallback to `resource_url` if needed
- If both missing but `id` present, build `https://www.discogs.com/release/{id}`

Implementation:
- `src/main/java/com/hctamlyniv/discogs/DiscogsApiClient.java` (`fetchWishlist`, `fetchWishlistReleaseIds`)
- `src/main/java/Server/routes/DiscogsRoutes.java` (`/api/discogs/wishlist`)

### Wantlist (Wishlist) Add

Used when the user clicks "add to wishlist" on a match.

- `POST /users/{username}/wants`

VinylMatch request:
- Form-encoded body:
  - `release_id={id}`
  - `notes=Added via VinylMatch`

Error semantics we handle:
- `409` is treated as idempotent success (already in wantlist).

Implementation:
- `src/main/java/com/hctamlyniv/discogs/DiscogsApiClient.java` (`addToWantlist`)
- `src/main/java/Server/routes/DiscogsRoutes.java` (`/api/discogs/wishlist/add`)

### Collection (Folder 0) Read

Used for checking whether a matched release is already in the user's collection.

- `GET /users/{username}/collection/folders/0/releases`

VinylMatch current behavior:
- Fetches page 1 only (up to `per_page=100`) and treats those IDs as "in collection".
- If a user has more than 100 collection items, this is an approximation.

Implementation:
- `src/main/java/com/hctamlyniv/discogs/DiscogsApiClient.java` (`fetchCollectionReleaseIds`)
- `src/main/java/com/hctamlyniv/DiscogsService.java` (`lookupLibraryFlags`)

## Likely Useful Discogs Database Endpoints (Not Used Yet)

These are common Discogs database lookups that would help VinylMatch get more precise "vinyl" matches and richer metadata. Confirm specifics against the official docs before implementing.

- `GET /releases/{release_id}`
  - Use when we want definitive release metadata (formats, tracklist, identifiers like barcode/UPC/EAN).
  - Typical fields of interest for VinylMatch: `tracklist`, `formats`, `identifiers`, `artists`, `labels`, `country`, `year`/`released`.
- `GET /masters/{master_id}/versions`
  - Use to pick a specific release variant (vinyl vs CD, specific country/year/label) after matching a master.
- `GET /database/search` additional filtering
  - Consider adding `format=Vinyl` (and possibly `country`, `year`) once we want to bias the search hard toward vinyl pressings instead of "best overall match".

## OAuth 1.0a Connect Flow (VinylMatch)

VinylMatch supports a full OAuth connect flow in addition to manual token login.

Discogs endpoints used:
- `POST https://api.discogs.com/oauth/request_token`
- `GET  https://www.discogs.com/oauth/authorize?oauth_token=...`
- `POST https://api.discogs.com/oauth/access_token`
- `GET  https://api.discogs.com/oauth/identity` (verify + fetch username)

VinylMatch routes:
- `POST /api/discogs/oauth/start` -> returns `authorizeUrl`
- `GET  /api/discogs/oauth/callback` -> exchanges verifier for access token + secret, stores session
- `GET  /api/discogs/oauth/status` -> whether OAuth is configured

Implementation:
- `src/main/java/Server/auth/DiscogsOAuthService.java`
- `src/main/java/Server/routes/DiscogsRoutes.java`
- `src/main/java/Server/session/DiscogsSessionStore.java` (encrypt-at-rest)

## VinylMatch API Surface (Non-Discogs, But Relevant)

These are VinylMatch endpoints that wrap Discogs behavior and enforce server-side header/auth rules:
- `GET  /api/discogs/status` (logged-in state + username + OAuth configured)
- `POST /api/discogs/login` (manual personal access token -> verifies via `/oauth/identity`)
- `POST /api/discogs/logout`
- `POST /api/discogs/search` (single album lookup)
- `POST /api/discogs/batch` (multi-track lookup with per-request dedupe)
- `GET  /api/discogs/wishlist` (proxy to `/users/{username}/wants`)
- `POST /api/discogs/wishlist/add` (proxy to `POST /users/{username}/wants`)
- `POST /api/discogs/library-status` (best-effort flags via wantlist + collection)

## Matching Strategy (What Works Well)

Current multi-pass approach in `src/main/java/com/hctamlyniv/DiscogsService.java`:
- Prefer curated links (Redis curated store) when present.
- Prefer barcode matches when barcode is available.
- Use free-text `q` searches first (fast, resilient), then structured parameters (`artist`, `release_title`, `track`, `year`) with `type=master` preferred.
- If result is a `master`, resolve to `main_release` for actions that require a `release_id`.

Practical Discogs Search Tips:
- Normalize artist aggressively (strip diacritics, drop feat/with) but keep special cases like `AC/DC` intact.
- Normalize album lightly for the second pass (strip diacritics, canonicalize whitespace).
- Only trust a cached result as "final" if it is not a generic Discogs web search URL.

## Common Pitfalls

- Calling Discogs directly from frontend JS: not recommended (User-Agent header + CORS issues). Keep API calls server-side.
- Treating transient failures as permanent: avoid caching fallback search URLs when upstream returns `429` or `5xx`.
- Assuming wantlist/collection page 1 covers everything: it does not for large libraries. If we need full coverage, implement paging with backoff.
- Relying on optional fields (`uri`, `thumb`, etc.): always have stable-ID fallbacks (`id` -> `/release/{id}`).

## Handy Examples (Do Not Paste Real Tokens)

Personal access token request:
```bash
curl -sS \
  -H "User-Agent: VinylMatch/1.0" \
  -H "Authorization: Discogs token=YOUR_TOKEN" \
  "https://api.discogs.com/database/search?type=release&artist=daft%20punk&release_title=homework&per_page=5&sort=relevance"
```

Wantlist page 2:
```bash
curl -sS \
  -H "User-Agent: VinylMatch/1.0" \
  -H "Authorization: Discogs token=YOUR_TOKEN" \
  "https://api.discogs.com/users/USERNAME/wants?page=2&per_page=50&sort=added&sort_order=desc"
```
