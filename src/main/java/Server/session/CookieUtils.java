package Server.session;

import Server.http.HttpUtils;
import com.sun.net.httpserver.HttpExchange;

import java.util.List;

/**
 * Utilities for parsing and setting HTTP cookies.
 */
public final class CookieUtils {

    private CookieUtils() {}

    /**
     * Retrieves a cookie value by name from the request headers.
     */
    public static String getCookie(HttpExchange exchange, String name) {
        if (exchange == null || name == null) {
            return null;
        }
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return null;
        }
        for (String header : cookies) {
            if (header == null) continue;
            String[] parts = header.split(";\\s*");
            for (String part : parts) {
                int idx = part.indexOf('=');
                if (idx <= 0) continue;
                String key = part.substring(0, idx).trim();
                String value = part.substring(idx + 1).trim();
                if (name.equals(key)) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Sets a cookie with standard security attributes.
     */
    public static void setCookie(HttpExchange exchange, String name, String value, int maxAgeSeconds, boolean httpOnly) {
        String sanitized = (value == null) ? "" : value.replaceAll("[^A-Za-z0-9_-]", "");
        StringBuilder cookie = new StringBuilder();
        cookie.append(name).append("=").append(sanitized);
        cookie.append("; Path=/");
        cookie.append("; Max-Age=").append(Math.max(0, maxAgeSeconds));
        if (httpOnly) {
            cookie.append("; HttpOnly");
        }
        cookie.append("; SameSite=Strict");
        if (HttpUtils.isSecureRequest(exchange)) {
            cookie.append("; Secure");
        }
        exchange.getResponseHeaders().add("Set-Cookie", cookie.toString());
    }

    /**
     * Clears a cookie by setting its Max-Age to 0.
     */
    public static void clearCookie(HttpExchange exchange, String name) {
        setCookie(exchange, name, "", 0, true);
    }
}
