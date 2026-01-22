# VinylMatch

VinylMatch is a lightweight web app + API that matches Spotify playlist tracks to their corresponding vinyl releases on Discogs (and optionally links out to other vinyl webstores).

It's built for collectors, DJs, archivists, and anyone who wants to connect a digital playlist to physical releases without manually searching every record.

## Features

- Spotify OAuth login (hosted multi-user, session-cookie based)
- Playlist import + track metadata extraction
- Progressive Discogs matching with caching and safe fallbacks
- Optional Discogs user token login (wishlist + library status)
- Custom vendor links via `config/vendors.json`
- Built-in rate limiting and structured JSON error responses

## How It Works

1. Log in with Spotify
2. Load a playlist
3. For each track, compute a match candidate (Discogs master/release)
4. Display results and vendor links in the web UI

## Matching Strategy (Discogs)

The matching engine (`DiscogsService`) uses progressive passes:

- Free-text search (artist + album)
- Light/strict normalization to reduce noise (punctuation, formatting, etc.)
- Structured search (master/release + optional year/track hints)
- Fallback to a Discogs web search URL if API matching is unavailable or inconclusive

## Run Locally

### Requirements

- Java 21+
- Maven 3.8+ (or use the bundled Maven in `./.tools/maven/...` on Windows)
- Spotify API credentials (`SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`)
- Optional: Discogs token (`DISCOGS_TOKEN`) for API matching; without it, the app falls back to Discogs web search links

### Setup

1) Clone

```bash
git clone https://github.com/Loues000/VinylMatch.git
cd VinylMatch
```

2) Configure environment

```bash
cp config/env.example .env
```

3) Build and test

```bash
mvn test
mvn package
```

The frontend is served as static files from `src/main/frontend/`.

Windows (PowerShell, with bundled Maven):

```powershell
.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test
.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd package
```

4) Run

```bash
java -jar target/VinylMatch.jar
```

Open `http://localhost:8888/`.

### Windows scripts

```powershell
.\scripts\setup.ps1
.\scripts\run.ps1 -Build
```

## Hosted Deployment

VinylMatch is designed to run as a hosted multi-user app:

- Users authenticate via Spotify OAuth
- Sessions are stored in memory and identified by secure HTTP cookies
- If you run behind a reverse proxy, forward `X-Forwarded-Proto` so secure cookies work correctly

### Configuration (Environment Variables)

See `config/env.example` for a complete template.

| Variable | Required | Description |
|----------|----------|-------------|
| `SPOTIFY_CLIENT_ID` | Yes | Spotify OAuth client ID |
| `SPOTIFY_CLIENT_SECRET` | Yes | Spotify OAuth client secret |
| `SPOTIFY_REDIRECT_URI` | No | OAuth callback URL (defaults to `http://127.0.0.1:PORT/api/auth/callback`) |
| `PUBLIC_BASE_URL` | Recommended | Public base URL (e.g. `https://vinylmatch.example.com`) |
| `DISCOGS_TOKEN` | Optional | Default Discogs token used for API matching |
| `DISCOGS_USER_AGENT` | Recommended | User-Agent for Discogs API (required by Discogs TOS) |
| `PORT` | No | Server port (default `8888`) |
| `CORS_ALLOWED_ORIGINS` | No | Comma-separated allowed origins |
| `RATE_LIMIT_PER_MINUTE` | No | Requests/minute per client+path (default `240`) |
| `RATE_LIMIT_BURST` | No | Burst capacity (default `max(30, perMinute/4)`) |

### Spotify Developer Setup

1. Create an app in the Spotify Developer Dashboard
2. Add the redirect URI:
   - Local: `http://127.0.0.1:8888/api/auth/callback`
   - Local (IPv6): `http://[::1]:8888/api/auth/callback`
   - Hosted: `https://your-domain.com/api/auth/callback`
3. Set `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET`

Note: Spotify does not allow `localhost` as a redirect URI. Use `127.0.0.1` (or `[::1]`) and open the app via that same host.

### Docker

```bash
docker build -t vinylmatch .
docker run -d \
  --name vinylmatch \
  -p 8888:8888 \
  -e SPOTIFY_CLIENT_ID=your_client_id \
  -e SPOTIFY_CLIENT_SECRET=your_client_secret \
  -e PUBLIC_BASE_URL=https://your-domain.com \
  -e DISCOGS_TOKEN=your_discogs_token \
  -e DISCOGS_USER_AGENT="VinylMatch/1.0 (+your_email)" \
  -v vinylmatch-cache:/app/cache \
  vinylmatch
```

### Docker Compose (example)

```yaml
version: '3.8'
services:
  vinylmatch:
    build: .
    ports:
      - "8888:8888"
    environment:
      - SPOTIFY_CLIENT_ID=${SPOTIFY_CLIENT_ID}
      - SPOTIFY_CLIENT_SECRET=${SPOTIFY_CLIENT_SECRET}
      - PUBLIC_BASE_URL=${PUBLIC_BASE_URL}
      - DISCOGS_TOKEN=${DISCOGS_TOKEN}
      - DISCOGS_USER_AGENT=VinylMatch/1.0
    volumes:
      - vinylmatch-cache:/app/cache
    restart: unless-stopped
volumes:
  vinylmatch-cache:
```

## Custom Vendor Links

Create `config/vendors.json` to add/override vendor links:

```json
{
  "vendors": [
    {
      "id": "discogs-market",
      "label": "D",
      "name": "Discogs Marketplace",
      "urlTemplate": "https://www.discogs.com/sell/list?q={query}&format=Vinyl",
      "enabled": true
    }
  ]
}
```

See `config/vendors.json.example` for more examples.

## API

The server exposes REST endpoints under `/api/*`.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/auth/status` | GET | Check Spotify login state |
| `/api/auth/login` | POST | Start Spotify OAuth flow |
| `/api/auth/callback` | GET | OAuth callback handler |
| `/api/auth/logout` | POST | End session |
| `/api/playlist?id={playlist_id}` | GET | Playlist details + Discogs matches |
| `/api/user/playlists` | GET | Current user's playlists |
| `/api/discogs/search` | POST | Search Discogs by artist/album/year/track |
| `/api/discogs/batch` | POST | Batch search for multiple tracks |
| `/api/discogs/status` | GET | Discogs session status |
| `/api/discogs/login` | POST | Discogs token login (per user) |
| `/api/discogs/logout` | POST | Discogs logout |
| `/api/discogs/wishlist` | GET | Discogs wishlist (requires Discogs login) |
| `/api/discogs/wishlist/add` | POST | Add release to wantlist |
| `/api/discogs/library-status` | POST | Wishlist/collection flags for URLs |
| `/api/discogs/curation/candidates` | POST | Candidate matches (admin/curation) |
| `/api/discogs/curation/save` | POST | Save curated match |
| `/api/config/vendors` | GET | Vendor config for frontend |

## Project Structure (high level)

- `src/main/java/Server/` — HTTP server, routes, sessions, rate limiting
- `src/main/java/com/hctamlyniv/discogs/` — Discogs client, cache, normalization, models
- `src/main/java/com/hctamlyniv/DiscogsService.java` — Discogs matching facade
- `src/main/frontend/` — static frontend (HTML/CSS/JS)

## Notes

- No Discogs token: matching falls back to Discogs web search URLs.
- Sessions are stored in memory; restarting the server logs users out.
- The app only uses metadata; it does not download or store any audio.

## License

See `License.txt`.
