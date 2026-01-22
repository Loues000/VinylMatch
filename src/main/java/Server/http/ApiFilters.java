package Server.http;

import Server.http.filters.RateLimitingFilter;
import com.sun.net.httpserver.Filter;

public final class ApiFilters {

    private static final RateLimiter RATE_LIMITER = RateLimiter.fromEnv();
    private static final Filter RATE_LIMITING_FILTER = new RateLimitingFilter(RATE_LIMITER);

    private ApiFilters() {}

    public static Filter rateLimiting() {
        return RATE_LIMITING_FILTER;
    }
}

