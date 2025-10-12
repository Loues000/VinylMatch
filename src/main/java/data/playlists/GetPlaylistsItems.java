package data.playlists;

import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.specification.Episode;
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.requests.data.playlists.GetPlaylistsItemsRequest;
import org.apache.hc.core5.http.ParseException;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class GetPlaylistsItems {
    private static final String accessToken = "BQDVQU4Lb9jKS-BPHLNe82gptEUwG7uvjurBRYOLFajI83lhSSArTlTWp1pFRjhDYxPGLKzNHzENBp07E7C5BXav2lg9IQU_dkn1kfMMmxAy8xagGtGUrvpU20rKIQluD47Yd1Y3rsjLlbk0CB71w4pOJt5KIqhClMDesm3sMwv87O-rNTlBx9wTotgjkNFzZ43SnvDEcEB6hres1s5d2eWn_9Q4_CLEhGkJlcKmQ0CdnVY7L4QQ4HIrnlI";
    private static final String playlistId = "4WWqTgCis6I4iDA703CIHu";

    private static final SpotifyApi spotifyApi = new SpotifyApi.Builder()
            .setAccessToken(accessToken)
            .build();
    private static final GetPlaylistsItemsRequest getPlaylistsItemsRequest = spotifyApi
            .getPlaylistsItems(playlistId)
//          .fields("description")
//          .limit(10)
//          .offset(0)
//          .market(CountryCode.SE)
//          .additionalTypes("track,episode")
            .build();

    public static void getPlaylistsItems_Sync() {
        try {
            final Paging<PlaylistTrack> playlistTrackPaging = getPlaylistsItemsRequest.execute();

            System.out.println("Total: " + playlistTrackPaging.getTotal());
            System.out.println("--------------------------------------");
            // Anzahl der Items auf dieser Seite
            PlaylistTrack[] items = playlistTrackPaging.getItems();

            for (int i = 0; i < items.length; i++) {
                Object item = items[i].getTrack();

                if (item instanceof Track track) {
                    System.out.println("Track name: " + track.getName());
                    System.out.println("Artist: " + track.getArtists()[0].getName());
                } else if (item instanceof Episode episode) {
                    System.out.println("Episode title: " + episode.getName());
                    System.out.println("Episode's show: " + episode.getShow().getName());
                } else {
                    System.out.println("Unknown item type: " + item.getClass().getName());
                }

                System.out.println("--------------------------------------");
            }

        } catch (IOException | SpotifyWebApiException | ParseException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    public static void getPlaylistsItems_Async() {
        try {
            final CompletableFuture<Paging<PlaylistTrack>> pagingFuture = getPlaylistsItemsRequest.executeAsync();

            // Thread free to do other tasks...

            // Example Only. Never block in production code.
            final Paging<PlaylistTrack> playlistTrackPaging = pagingFuture.join();

            System.out.println("Total: " + playlistTrackPaging.getTotal());
            System.out.println("Track's first artist: " + ((Track) playlistTrackPaging.getItems()[0].getTrack()).getArtists()[0]);
            System.out.println("Episode's show: " + ((Episode) playlistTrackPaging.getItems()[0].getTrack()).getShow());
        } catch (CompletionException e) {
            System.out.println("Error: " + e.getCause().getMessage());
        } catch (CancellationException e) {
            System.out.println("Async operation cancelled.");
        }
    }

    public static void main(String[] args) {
        getPlaylistsItems_Sync();
        //getPlaylistsItems_Async();
    }
}
