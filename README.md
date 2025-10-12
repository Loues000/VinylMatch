# 🎵 VinylMatch

VinylMatch is a lightweight web application and API for **matching Spotify playlist tracks to their corresponding vinyl releases on Discogs and other vinyl webstores**.  

It is designed for collectors, DJs, archivists, and enthusiasts who want to connect their digital collections with physical records by automatically finding the correct Discogs album/master entries for tracks in a given Spotify playlist.

---

## 📖 Overview

VinylMatch follows a simple but powerful workflow:

1. **Load a Spotify playlist**  
   You paste a Spotify playlist link, and VinylMatch fetches all track and album data via the Spotify Web API.

2. **Analyze track metadata**  
   For each track, the app collects the tracks metaddata.

3. **Find matching vinyl releases**  
   Using the [Discogs API](https://www.discogs.com/developers), VinylMatch searches for likely matches.
   
4. **Display results**  
   For each playlist track, you get:
   - Original track metadata from Spotify  
   - Link to the matching Discogs album/master page 
   - Cover art preview

---

## 🔍 Matching procedure

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

## 💻 Running locally

You can run VinylMatch as a local web app and API server.

### Requirements
- **Java 17 or higher**
- **Spotify API Credentials** (Client ID & Client Secret)
- *(Optional)* Discogs API Token — needed for automatic matching, otherwise only web search links will be provided.

### Steps

1. **Clone the repository**
   ```bash
   git clone https://github.com/Loues000/VinylMatch.git
   cd VinylMatch

Set environment variables
In your shell or system environment:

export SPOTIFY_CLIENT_ID=your_client_id
export SPOTIFY_CLIENT_SECRET=your_client_secret
export DISCOGS_TOKEN=your_discogs_token
export DISCOGS_USER_AGENT="fitting project name (+your_contact_info)"

Build the project VinylMatch uses Maven:

mvn package

Run the server

java -jar target/VinylMatch.jar

The app will start a local web server, by default at:

http://localhost:8888/

    You will see a homepage where you can paste your Spotify playlist link.
    Log in to Spotify
    The first time you use it, you will be prompted to log in via Spotify OAuth.
    After login, your tokens are saved locally so future runs are quicker.

🌐 API Endpoints
VinylMatch also exposes REST endpoints for programmatic access:

    GET /api/playlist?id={playlist_id}
    Returns playlist details + matched Discogs URLs.
    POST /api/discogs/search
    Search Discogs directly by artist/album/year/track.
    GET /api/auth/status
    Check Spotify login state.

📂 Project structure

    com.hctamlyniv.* – Core logic, Spotify integration, Discogs matching
    Server.* – HTTP API server + JSON responses
    src/main/frontend – Static HTML/CSS/JS frontend

📝 Notes

    Without a Discogs token, matching will fall back to providing Discogs search URLs.
    Spotify barcode data is not always available — when missing, matching relies on fuzzy string search.
    The app does not download or store music; it only works with metadata.
