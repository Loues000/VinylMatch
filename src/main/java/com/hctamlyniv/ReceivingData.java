package com.hctamlyniv;

import Server.PlaylistData;
import Server.TrackData;
import com.hctamlyniv.spotify.PlaylistAssembler;
import com.hctamlyniv.spotify.SpotifyAlbumBatchLoader;
import com.hctamlyniv.spotify.SpotifyPlaylistReader;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.Album;
import se.michaelthelin.spotify.model_objects.specification.Playlist;
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator class for loading Spotify playlist data.
 * Delegates to specialized components for different concerns.
 */
public class ReceivingData {

    private static final Logger log = LoggerFactory.getLogger(ReceivingData.class);

    private final SpotifyApi spotifyApi;
    private final String playlistId;
    private final DiscogsService discogsService;

    private final SpotifyPlaylistReader playlistReader;
    private final SpotifyAlbumBatchLoader albumBatchLoader;
    private final PlaylistAssembler playlistAssembler;

    public ReceivingData(String accessToken, String playlistId, DiscogsService discogsService) {
        this.playlistId = playlistId;
        this.discogsService = discogsService;
        
        this.spotifyApi = new SpotifyApi.Builder()
                .setAccessToken(accessToken)
                .build();

        // Initialize specialized components
        this.playlistReader = new SpotifyPlaylistReader(spotifyApi);
        this.albumBatchLoader = new SpotifyAlbumBatchLoader(spotifyApi);
        this.playlistAssembler = new PlaylistAssembler(this.discogsService);
    }

    /**
     * Loads all playlist data without pagination limits.
     */
    public PlaylistData loadPlaylistData() {
        return loadPlaylistData(0, -1);
    }

    /**
     * Loads playlist data with pagination support.
     * 
     * @param requestedOffset Starting offset
     * @param requestedLimit Maximum tracks to load (-1 for all)
     * @return PlaylistData or null on error
     */
    public PlaylistData loadPlaylistData(int requestedOffset, int requestedLimit) {
        try {
            // 1. Get playlist metadata
            Playlist playlist = playlistReader.getPlaylist(playlistId);
            PlaylistAssembler.PlaylistMetadata metadata = playlistAssembler.extractMetadata(playlist);

            // 2. Fetch playlist items with pagination
            SpotifyPlaylistReader.PlaylistItemsResult itemsResult = 
                    playlistReader.getAllPlaylistItems(playlistId, requestedOffset, requestedLimit);

            List<PlaylistTrack> playlistTracks = itemsResult.items();

            // 3. Batch load album details
            Map<String, Album> albumDetailsMap = albumBatchLoader.loadAlbumDetailsForTracks(playlistTracks);

            // 4. Assemble track data
            List<TrackData> tracks = playlistAssembler.assembleTrackData(playlistTracks, albumDetailsMap);

            // 5. Build final result
            return playlistAssembler.assemblePlaylistData(
                    metadata,
                    tracks,
                    itemsResult.total(),
                    itemsResult.offset(),
                    itemsResult.nextOffset(),
                    itemsResult.hasMore()
            );

        } catch (Exception e) {
            log.warn("Failed to load playlist data: {}", e.getMessage(), e);
            return null;
        }
    }
}
