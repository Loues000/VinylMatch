package Server.auth;

import com.hctamlyniv.Config;
import com.hctamlyniv.discogs.DiscogsOAuth1;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles Discogs OAuth 1.0a start and callback token exchange.
 */
public class DiscogsOAuthService {

    private static final Logger log = LoggerFactory.getLogger(DiscogsOAuthService.class);
    private static final URI REQUEST_TOKEN_URI = URI.create("https://api.discogs.com/oauth/request_token");
    private static final URI ACCESS_TOKEN_URI = URI.create("https://api.discogs.com/oauth/access_token");
    private static final String AUTHORIZE_URL = "https://www.discogs.com/oauth/authorize";
    private static final int REQUEST_TOKEN_RETRY_LIMIT = 3;
    private static final long REQUEST_TOKEN_RETRY_BASE_DELAY_MS = 350L;

    private final String consumerKey;
    private final String consumerSecret;
    private final URI redirectUri;
    private final boolean redirectUriExplicit;
    private final HttpClient httpClient;
    private final Map<String, PendingRequestToken> pendingTokens = new ConcurrentHashMap<>();

    public DiscogsOAuthService() {
        this.consumerKey = trimOrNull(Config.getDiscogsConsumerKey());
        this.consumerSecret = trimOrNull(Config.getDiscogsConsumerSecret());
        RedirectUriResolution resolution = resolveRedirectUriFromConfig();
        this.redirectUri = resolution.redirectUri();
        this.redirectUriExplicit = resolution.explicit();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isConfigured() {
        return consumerKey != null && consumerSecret != null;
    }

    public boolean isRedirectUriExplicit() {
        return redirectUriExplicit;
    }

    public Optional<String> buildAuthorizationUrl(URI redirectUriOverride) {
        if (!isConfigured()) {
            return Optional.empty();
        }

        URI effectiveRedirectUri = redirectUri;
        if (!redirectUriExplicit && redirectUriOverride != null) {
            effectiveRedirectUri = redirectUriOverride;
        }
        if (effectiveRedirectUri == null) {
            return Optional.empty();
        }

        cleanupPendingTokens();
        String state = randomState();
        URI callbackWithState = DiscogsOAuth1.appendQueryParam(effectiveRedirectUri, "state", state);

        Map<String, String> oauthParams = DiscogsOAuth1.newOAuthParameters(consumerKey, null);
        oauthParams.put("oauth_callback", callbackWithState.toString());
        String authHeader = DiscogsOAuth1.buildAuthorizationHeader(
                "POST",
                REQUEST_TOKEN_URI,
                oauthParams,
                consumerSecret,
                null,
                null
        );

        HttpRequest request = HttpRequest.newBuilder(REQUEST_TOKEN_URI)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", authHeader)
                .header("User-Agent", resolveUserAgent())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        for (int attempt = 1; attempt <= REQUEST_TOKEN_RETRY_LIMIT; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    String bodySnippet = abbreviateForLog(response.body());
                    log.warn("Discogs request_token failed with status {}{}", status, bodySnippet == null ? "" : " (" + bodySnippet + ")");
                    if (isTransientStatus(status) && attempt < REQUEST_TOKEN_RETRY_LIMIT) {
                        sleepBackoff(attempt);
                        continue;
                    }
                    return Optional.empty();
                }

                Map<String, String> parsed = DiscogsOAuth1.parseFormEncoded(response.body());
                String requestToken = parsed.get("oauth_token");
                String requestTokenSecret = parsed.get("oauth_token_secret");
                String callbackConfirmed = parsed.get("oauth_callback_confirmed");
                if (requestToken == null || requestTokenSecret == null || !"true".equalsIgnoreCase(callbackConfirmed)) {
                    log.warn("Discogs request_token response missing required fields");
                    return Optional.empty();
                }

                pendingTokens.put(requestToken, new PendingRequestToken(requestTokenSecret, state, System.currentTimeMillis()));
                return Optional.of(AUTHORIZE_URL + "?oauth_token=" + DiscogsOAuth1.percentEncode(requestToken));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Discogs OAuth start interrupted");
                return Optional.empty();
            } catch (Exception e) {
                log.warn("Discogs OAuth start failed on attempt {}: {}", attempt, e.getMessage());
                if (isTransientException(e) && attempt < REQUEST_TOKEN_RETRY_LIMIT) {
                    sleepBackoff(attempt);
                    continue;
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<AccessTokenResponse> exchangeAccessToken(String requestToken, String verifier, String state) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        if (requestToken == null || requestToken.isBlank() || verifier == null || verifier.isBlank()) {
            return Optional.empty();
        }

        PendingRequestToken pending = pendingTokens.remove(requestToken);
        if (pending == null) {
            log.warn("Discogs OAuth callback uses unknown/expired request token");
            return Optional.empty();
        }
        if (state == null || state.isBlank() || !state.equals(pending.state())) {
            log.warn("Discogs OAuth callback state mismatch");
            return Optional.empty();
        }
        if ((System.currentTimeMillis() - pending.createdAt()) > (10 * 60 * 1000L)) {
            log.warn("Discogs OAuth callback request token expired");
            return Optional.empty();
        }

        Map<String, String> oauthParams = DiscogsOAuth1.newOAuthParameters(consumerKey, requestToken);
        oauthParams.put("oauth_verifier", verifier);
        String authHeader = DiscogsOAuth1.buildAuthorizationHeader(
                "POST",
                ACCESS_TOKEN_URI,
                oauthParams,
                consumerSecret,
                pending.requestTokenSecret(),
                null
        );

        HttpRequest request = HttpRequest.newBuilder(ACCESS_TOKEN_URI)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", authHeader)
                .header("User-Agent", resolveUserAgent())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Discogs access_token failed with status {}", response.statusCode());
                return Optional.empty();
            }
            Map<String, String> parsed = DiscogsOAuth1.parseFormEncoded(response.body());
            String accessToken = parsed.get("oauth_token");
            String accessTokenSecret = parsed.get("oauth_token_secret");
            if (accessToken == null || accessToken.isBlank() || accessTokenSecret == null || accessTokenSecret.isBlank()) {
                log.warn("Discogs access_token response missing token/secret");
                return Optional.empty();
            }
            return Optional.of(new AccessTokenResponse(accessToken, accessTokenSecret));
        } catch (Exception e) {
            log.warn("Discogs OAuth access token exchange failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public String getConsumerKey() {
        return consumerKey;
    }

    public String getConsumerSecret() {
        return consumerSecret;
    }

    private String resolveUserAgent() {
        String configured = Config.getDiscogsUserAgent();
        return (configured == null || configured.isBlank()) ? "VinylMatch/1.0" : configured.trim();
    }

    private void cleanupPendingTokens() {
        long cutoff = System.currentTimeMillis() - (10 * 60 * 1000L);
        pendingTokens.entrySet().removeIf(entry -> entry.getValue().createdAt() < cutoff);
    }

    private static String randomState() {
        byte[] stateBytes = new byte[24];
        new java.security.SecureRandom().nextBytes(stateBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
    }

    private static boolean isTransientStatus(int status) {
        return status == 429 || status >= 500;
    }

    private static boolean isTransientException(Throwable throwable) {
        return throwable instanceof java.io.IOException;
    }

    private static void sleepBackoff(int attempt) {
        long delay = REQUEST_TOKEN_RETRY_BASE_DELAY_MS * Math.max(1, attempt);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviateForLog(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 157) + "...";
    }

    private static RedirectUriResolution resolveRedirectUriFromConfig() {
        String configured = Config.getDiscogsRedirectUri();
        if (configured != null && !configured.isBlank()) {
            try {
                return new RedirectUriResolution(new URI(configured.trim()), true);
            } catch (URISyntaxException ignored) {
                // Fall through to derived defaults.
            }
        }

        String publicBaseUrl = Config.getPublicBaseUrl();
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            String base = publicBaseUrl.trim();
            if (!base.endsWith("/")) {
                base += "/";
            }
            try {
                return new RedirectUriResolution(new URI(base + "api/discogs/oauth/callback"), true);
            } catch (URISyntaxException ignored) {
                // Fall through to local default.
            }
        }

        int port = Config.getPort();
        try {
            return new RedirectUriResolution(new URI("http://127.0.0.1:" + port + "/api/discogs/oauth/callback"), false);
        } catch (URISyntaxException e) {
            return new RedirectUriResolution(null, false);
        }
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record PendingRequestToken(String requestTokenSecret, String state, long createdAt) {}

    public record AccessTokenResponse(String token, String tokenSecret) {}

    private record RedirectUriResolution(URI redirectUri, boolean explicit) {}
}
