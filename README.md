# 🎵 VinylMatch

VinylMatch is a lightweight web application and API for **matching Spotify playlist tracks to their corresponding vinyl releases on Discogs and other vinyl webstores**.  

It is designed for collectors, DJs, archivists, and enthusiasts who want to connect their digital collections with physical records by automatically finding the correct Discogs album/master entries for tracks in a given Spotify playlist.

---

## 📖 Overview

VinylMatch follows a simple but powerful workflow:

1. **Load a Spotify playlist**  
   You paste a Spotify playlist link, and VinylMatch fetches all track and album data via the Spotify Web API.

2. **Analyze track metadata**  
   For each track, the app collects the track's metadata.

3. **Find matching vinyl releases**  
   Using the [Discogs API](https://www.discogs.com/developers), VinylMatch searches for likely matches.
   
4. **Display results**  
   For each playlist track, you get:
   - Original track metadata from Spotify  
   - Link to the matching Discogs album/master page 
   - Cover art preview
   - Links to vinyl stores (HHV, JPC, Amazon, and custom vendors)

---

## 🔍 Matching Procedure

The matching engine (see `DiscogsService`) uses **progressive search passes**:

- **Pass A – Direct text search:**  
  Strictly normalized artist name + album title.

- **Pass B – Light normalization:**  
  Removal of diacritics, marketing suffixes, and formatting noise.

- **Pass C – Structured queries:**  
  Using Discogs search parameters (`type=master` or `type=release`) with year filters.

- **Pass D – Fallbacks:**  
  Searches without year, then finally provides a Discogs web search link if nothing exact is found.

This approach ensures high hit rates even with messy or incomplete metadata.

---

## 💻 Running Locally

### Requirements
- **Java 21 or higher**
- **Maven 3.8+**
- **Spotify API Credentials** (Client ID & Client Secret)
- *(Optional)* Discogs API Token — needed for automatic matching, otherwise only web search links will be provided.

### Steps

1. **Clone the repository**
```bash
git clone https://github.com/Loues000/VinylMatch.git
cd VinylMatch
```

2. **Configure environment variables**

Copy the example environment file and fill in your credentials:
```bash
cp config/env.example .env
# Edit .env with your actual values
```

Or set environment variables directly:
```bash
# Required
export SPOTIFY_CLIENT_ID=your_client_id
export SPOTIFY_CLIENT_SECRET=your_client_secret

# Recommended (for Discogs matching)
export DISCOGS_TOKEN=your_discogs_token
export DISCOGS_USER_AGENT="VinylMatch/1.0 (+your_contact_info)"
```

> **Note:** Never commit `.env` files with real credentials. The `config/env.example` file contains safe placeholder values.

**Windows (PowerShell) quickstart**
```powershell
.\scripts\setup.ps1
.\scripts\run.ps1 -Build
```

3. **Build the project**
```bash
mvn package
```

4. **Run the server**
```bash
java -jar target/VinylMatch.jar
```

The app will start a local web server at **http://localhost:8888/**

---

## 🌐 Hosted Deployment

VinylMatch supports multi-user hosted deployment with session-based authentication.

### Configuration

All configuration is done via environment variables. See [`config/env.example`](config/env.example) for a complete template.

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `SPOTIFY_CLIENT_ID` | Yes | Spotify OAuth client ID |
| `SPOTIFY_CLIENT_SECRET` | Yes | Spotify OAuth client secret |
| `SPOTIFY_REDIRECT_URI` | No | OAuth callback URL (defaults to `http://127.0.0.1:PORT/api/auth/callback`) |
| `PUBLIC_BASE_URL` | Recommended | Your public URL (e.g., `https://vinylmatch.example.com`) |
| `DISCOGS_TOKEN` | Recommended | Discogs personal access token for API matching |
| `DISCOGS_USER_AGENT` | Recommended | User-Agent for Discogs API (required by Discogs TOS) |
| `PORT` | No | Server port (default: `8888`) |
| `CORS_ALLOWED_ORIGINS` | No | Comma-separated list of allowed CORS origins |

### Spotify Developer Setup

1. Go to [Spotify Developer Dashboard](https://developer.spotify.com/dashboard)
2. Create a new application
3. Add your redirect URI to the app settings:
   - For local: `http://127.0.0.1:8888/api/auth/callback`
   - For hosted: `https://your-domain.com/api/auth/callback`
4. Copy the Client ID and Client Secret

### Docker Deployment

```bash
# Build
docker build -t vinylmatch .

# Run
docker run -d \
  --name vinylmatch \
  -p 8888:8888 \
  -e SPOTIFY_CLIENT_ID=your_client_id \
  -e SPOTIFY_CLIENT_SECRET=your_client_secret \
  -e SPOTIFY_REDIRECT_URI=https://your-domain.com/api/auth/callback \
  -e PUBLIC_BASE_URL=https://your-domain.com \
  -e DISCOGS_TOKEN=your_discogs_token \
  -e DISCOGS_USER_AGENT="VinylMatch/1.0 (+your_email)" \
  -v vinylmatch-cache:/app/cache \
  vinylmatch
```

### Docker Compose Example

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
      - SPOTIFY_REDIRECT_URI=${SPOTIFY_REDIRECT_URI}
      - PUBLIC_BASE_URL=${PUBLIC_BASE_URL}
      - DISCOGS_TOKEN=${DISCOGS_TOKEN}
      - DISCOGS_USER_AGENT=VinylMatch/1.0
    volumes:
      - vinylmatch-cache:/app/cache
    restart: unless-stopped

volumes:
  vinylmatch-cache:
```

---

## 🛒 Custom Vinyl Store Links

You can add custom vinyl store search links by creating `config/vendors.json`:

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

---

## 🌐 API Endpoints

VinylMatch exposes REST endpoints for programmatic access:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/playlist?id={playlist_id}` | GET | Returns playlist details + matched Discogs URLs |
| `/api/user/playlists` | GET | Returns current user's playlists |
| `/api/discogs/search` | POST | Search Discogs directly by artist/album/year/track |
| `/api/discogs/batch` | POST | Batch search for multiple tracks |
| `/api/auth/status` | GET | Check Spotify login state |
| `/api/auth/login` | POST | Start Spotify OAuth flow |
| `/api/auth/callback` | GET | OAuth callback handler |
| `/api/auth/logout` | POST | End session |

---

## 📂 Project Structure

```
VinylMatch/
├── src/main/java/
│   ├── com/hctamlyniv/          # Core logic
│   │   ├── Config.java          # Configuration management
│   │   ├── DiscogsService.java  # Discogs API client + matching
│   │   ├── Main.java            # Application entry point
│   │   ├── ReceivingData.java   # Playlist data coordinator
│   │   ├── SpotifyAuth.java     # Legacy OAuth (for CLI)
│   │   └── spotify/             # Spotify API components
│   └── Server/
│       ├── ApiServer.java       # HTTP server + route wiring
│       ├── auth/                # OAuth service
│       ├── cache/               # Playlist caching
│       ├── http/                # HTTP utilities
│       ├── routes/              # API route handlers
│       └── session/             # Session management
├── src/main/frontend/           # Static web frontend
│   ├── dist/                    # JavaScript modules
│   ├── styles/                  # CSS
│   └── *.html                   # HTML pages
├── cache/                       # Runtime caches (gitignored)
├── config/                      # Custom configuration
├── Dockerfile                   # Docker build
└── pom.xml                      # Maven build config
```

---

## 📝 Notes

- Without a Discogs token, matching will fall back to providing Discogs search URLs.
- Spotify barcode data is not always available — when missing, matching relies on fuzzy string search.
- The app does not download or store music; it only works with metadata.
- Sessions are stored in-memory; restarting the server will require users to re-login.

---

## 📜 License

See [License.txt](License.txt) for details.
