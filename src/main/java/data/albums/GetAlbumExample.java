package data.albums;

import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.specification.Album;
import se.michaelthelin.spotify.requests.data.albums.GetAlbumRequest;
import org.apache.hc.core5.http.ParseException;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class GetAlbumExample {
    private static final String accessToken = "BQB5wpYHbveRluA-UY5EHmTS7is4pWhjhq68hdTj3ItwV8iocq_URzC_nC-xDlowsl5RHqTbjicz15Xn_8nKN9SkZp90kM88lYf7L0sTOnmbCFWNJaSIp5JtQHkzo32kUfeqMNeXgnVmCv9v43c9evnsWj3kKuwinOg90j211BVuMxDve9IUulU2FQravgmNYlc7EPswCbCujH00cAwm10S5UsJpRBD6T8QYR6HoMzrt5UG6u1KMM2fYIgw";
    private static final String id = "18NOKLkZETa4sWwLMIm0UZ";

    private static final SpotifyApi spotifyApi = new SpotifyApi.Builder()
            .setAccessToken(accessToken)
            .build();
    private static final GetAlbumRequest getAlbumRequest = spotifyApi.getAlbum(id)
//          .market(CountryCode.SE)
            .build();

    public static void getAlbum_Sync() {
        try {
            final Album album = getAlbumRequest.execute();

            System.out.println("Name: " + album.getName() + " by: " + album.getArtists());
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    public static void getAlbum_Async() {
        try {
            final CompletableFuture<Album> albumFuture = getAlbumRequest.executeAsync();

            // Thread free to do other tasks...

            // Example Only. Never block in production code.
            final Album album = albumFuture.join();

            System.out.println("Name: " + album.getName());
        } catch (CompletionException e) {
            System.out.println("Error: " + e.getCause().getMessage());
        } catch (CancellationException e) {
            System.out.println("Async operation cancelled.");
        }
    }

    public static void main(String[] args) {
        getAlbum_Sync();
        getAlbum_Async();
    }
}