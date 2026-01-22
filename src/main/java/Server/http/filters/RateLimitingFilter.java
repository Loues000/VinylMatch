package Server.http.filters;

import Server.http.HttpUtils;
import Server.http.RateLimiter;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

public class RateLimitingFilter extends Filter {

    private final RateLimiter limiter;

    public RateLimitingFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getPath() : "";
        if (path != null && path.startsWith("/api/")) {
            String key = clientIp(exchange) + "|" + path;
            RateLimiter.Result result = limiter.tryAcquire(key);
            if (!result.allowed()) {
                exchange.getResponseHeaders().set("Retry-After", Integer.toString(result.retryAfterSeconds()));
                HttpUtils.addCorsHeaders(exchange);
                HttpUtils.sendApiError(exchange, 429, "rate_limited", "Too many requests");
                return;
            }
        }
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Rate limits API requests";
    }

    private static String clientIp(HttpExchange exchange) {
        List<String> forwarded = exchange.getRequestHeaders().get("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            String v = forwarded.get(0);
            if (v != null && !v.isBlank()) {
                String first = v.split(",")[0].trim();
                if (!first.isBlank()) return first;
            }
        }
        InetSocketAddress remote = exchange.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) return "unknown";
        return remote.getAddress().getHostAddress();
    }
}

