package data.albums;

import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.specification.Album;
import se.michaelthelin.spotify.requests.data.albums.GetSeveralAlbumsRequest;
import org.apache.hc.core5.http.ParseException;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class GetSeveralAlbumsExample {
    private static final String accessToken = "BQBIQA3z_pRQWiMxW1CNGSK7bS5ZLR3m_Fa5KCbuR8hqzBEMJiuNaD4SMJw5UyBCoL3rkyt8I1E9pdQGjVwolQodC25bF6QOATRdGkPEf0m-wdpngqkjOoo-5PKIAZwlCaoPKjWvlBWWlMZc7aZ88wUeSi9u7V--LPWkJ8Ea5GCRlSBzfmQYXnMINk6I9joMOZxXyf1LCl6VW9ViuDptoyuVXy95yP3ie2SAfeBS_DrlaqFnu48BtBF2YPw";
    private static final String[] ids = new String[]{"0LcJLqbBmaGUft1e9Mm8HV"};

    private static final SpotifyApi spotifyApi = new SpotifyApi.Builder()
            .setAccessToken(accessToken)
            .build();
    private static final GetSeveralAlbumsRequest getSeveralAlbumsRequest = spotifyApi.getSeveralAlbums(ids)
//          .market(CountryCode.SE)
            .build();

    public static void getSeveralAlbums_Sync() {
        try {
            final Album[] albums = getSeveralAlbumsRequest.execute();

            System.out.println("Length: " + albums.length);
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    public static void getSeveralAlbums_Async() {
        try {
            final CompletableFuture<Album[]> albumsFuture = getSeveralAlbumsRequest.executeAsync();

            // Thread free to do other tasks...

            // Example Only. Never block in production code.
            final Album[] albums = albumsFuture.join();

            System.out.println("Length: " + albums.length);
        } catch (CompletionException e) {
            System.out.println("Error: " + e.getCause().getMessage());
        } catch (CancellationException e) {
            System.out.println("Async operation cancelled.");
        }
    }

    public static void main(String[] args) {
        getSeveralAlbums_Sync();
        getSeveralAlbums_Async();
    }
}