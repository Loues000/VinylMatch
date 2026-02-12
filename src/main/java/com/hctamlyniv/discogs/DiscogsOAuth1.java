package com.hctamlyniv.discogs;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared OAuth 1.0a helpers for Discogs request signing and token exchanges.
 */
public final class DiscogsOAuth1 {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private DiscogsOAuth1() {}

    public static Map<String, String> newOAuthParameters(String consumerKey, String token) {
        Map<String, String> params = new HashMap<>();
        params.put("oauth_consumer_key", consumerKey);
        params.put("oauth_nonce", randomNonce());
        params.put("oauth_signature_method", "HMAC-SHA1");
        params.put("oauth_timestamp", String.valueOf(System.currentTimeMillis() / 1000L));
        params.put("oauth_version", "1.0");
        if (token != null && !token.isBlank()) {
            params.put("oauth_token", token);
        }
        return params;
    }

    public static String buildAuthorizationHeader(
            String method,
            URI uri,
            Map<String, String> oauthParams,
            String consumerSecret,
            String tokenSecret,
            Map<String, String> formParams
    ) {
        Map<String, String> paramsForSignature = new HashMap<>();
        paramsForSignature.putAll(parseFormEncoded(uri == null ? null : uri.getRawQuery()));
        if (formParams != null) {
            for (Map.Entry<String, String> entry : formParams.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    paramsForSignature.put(entry.getKey(), entry.getValue());
                }
            }
        }
        for (Map.Entry<String, String> entry : oauthParams.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (!"oauth_signature".equals(entry.getKey())) {
                paramsForSignature.put(entry.getKey(), entry.getValue());
            }
        }

        String signature = sign(
                (method == null ? "GET" : method).toUpperCase(),
                normalizeBaseUri(uri),
                paramsForSignature,
                consumerSecret,
                tokenSecret
        );

        Map<String, String> authParams = new HashMap<>(oauthParams);
        authParams.put("oauth_signature", signature);
        return "OAuth " + buildOAuthHeaderParameters(authParams);
    }

    public static URI appendQueryParam(URI base, String key, String value) {
        if (base == null || key == null || key.isBlank()) {
            return base;
        }
        String existing = base.getRawQuery();
        String encodedKey = percentEncode(key);
        String encodedValue = percentEncode(value == null ? "" : value);
        String newQuery = (existing == null || existing.isBlank())
                ? (encodedKey + "=" + encodedValue)
                : (existing + "&" + encodedKey + "=" + encodedValue);
        try {
            return new URI(
                    base.getScheme(),
                    base.getRawAuthority(),
                    base.getRawPath(),
                    newQuery,
                    base.getRawFragment()
            );
        } catch (URISyntaxException e) {
            return base;
        }
    }

    public static Map<String, String> parseFormEncoded(String encoded) {
        Map<String, String> result = new HashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return result;
        }
        String[] parts = encoded.split("&");
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            int idx = part.indexOf('=');
            String key;
            String value;
            if (idx >= 0) {
                key = decode(part.substring(0, idx));
                value = decode(part.substring(idx + 1));
            } else {
                key = decode(part);
                value = "";
            }
            if (key != null && !key.isBlank()) {
                result.put(key, value == null ? "" : value);
            }
        }
        return result;
    }

    public static String toFormEncoded(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            joiner.add(percentEncode(entry.getKey()) + "=" + percentEncode(entry.getValue() == null ? "" : entry.getValue()));
        }
        return joiner.toString();
    }

    public static String percentEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String randomNonce() {
        byte[] bytes = new byte[18];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String sign(
            String method,
            String normalizedBaseUri,
            Map<String, String> normalizedParams,
            String consumerSecret,
            String tokenSecret
    ) {
        String normalizedParameterString = normalizeParameters(normalizedParams);
        String signatureBase = method + "&" + percentEncode(normalizedBaseUri) + "&" + percentEncode(normalizedParameterString);
        String key = percentEncode(consumerSecret == null ? "" : consumerSecret) + "&" + percentEncode(tokenSecret == null ? "" : tokenSecret);
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] raw = mac.doFinal(signatureBase.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign OAuth request", e);
        }
    }

    private static String normalizeParameters(Map<String, String> params) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(params.entrySet());
        entries.sort((a, b) -> {
            String keyA = percentEncode(a.getKey());
            String keyB = percentEncode(b.getKey());
            int keyCompare = keyA.compareTo(keyB);
            if (keyCompare != 0) {
                return keyCompare;
            }
            return percentEncode(a.getValue()).compareTo(percentEncode(b.getValue()));
        });

        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : entries) {
            joiner.add(percentEncode(entry.getKey()) + "=" + percentEncode(entry.getValue()));
        }
        return joiner.toString();
    }

    private static String normalizeBaseUri(URI uri) {
        if (uri == null) {
            return "";
        }
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        int port = uri.getPort();
        boolean includePort = port > 0
                && !(scheme.equals("http") && port == 80)
                && !(scheme.equals("https") && port == 443);
        String path = (uri.getPath() == null || uri.getPath().isBlank()) ? "/" : uri.getPath();
        return scheme + "://" + host + (includePort ? ":" + port : "") + path;
    }

    private static String buildOAuthHeaderParameters(Map<String, String> params) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(params.entrySet());
        entries.removeIf(entry -> entry.getKey() == null || !entry.getKey().startsWith("oauth_") || entry.getValue() == null);
        entries.sort(Map.Entry.comparingByKey());
        StringJoiner joiner = new StringJoiner(", ");
        for (Map.Entry<String, String> entry : entries) {
            joiner.add(percentEncode(entry.getKey()) + "=\"" + percentEncode(entry.getValue()) + "\"");
        }
        return joiner.toString();
    }
}
