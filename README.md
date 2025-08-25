# README – VINYL MATCH

Overview VINYL MATCH turns any Spotify playlist into a vinyl discovery and buying aid. Paste a playlist URL to see albums with artwork, a Discogs match, and quick links to retailers (HHV, JPC, Amazon).

Features

    Paste a Spotify playlist URL
    View playlist title, cover, tracks (album, artist, year, cover)
    Direct Discogs match or one-click search
    Vendor quick-links shown only when a Discogs match exists
    Recent playlists stored locally in the browser

Architecture

    Frontend: static HTML/CSS + TypeScript (compiled to ES modules in dist), served from src/main/frontend
    Backend: Java HTTP server with Spotify OAuth, REST endpoints, Discogs client

Configuration

    Credentials via Config.java:
        Reads ~/VinylMatch/spotify.properties (in user home)
        Environment variables override file values
    Required: SPOTIFY_CLIENT_ID, SPOTIFY_CLIENT_SECRET
    Optional: DISCOGS_TOKEN, DISCOGS_USER_AGENT (default: VinylMatch/OpenSourceUser)

Quick start

    Provide credentials via env vars or ~/VinylMatch/spotify.properties
    Build frontend: run tsc in project root (TypeScript required)
    Run backend: launch com.hctamlyniv.Main (Java 17+)
    Open http://localhost:8888 and paste a Spotify playlist link
    First run: complete Spotify login in the browser (callback on 127.0.0.1:8890)

API

    GET /api/playlist?id={spotifyPlaylistId} → playlist JSON (name, cover, url, tracks with album/artist/year/cover/Discogs URL)
    POST /api/discogs/search → JSON { artist, album, releaseYear?, track? } → { url } if matched


License MIT License. Contributions welcome. Please do not include credentials or tokens in commits or issues.
