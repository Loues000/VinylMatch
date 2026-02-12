package Server.http;

import Server.http.filters.CorrelationIdFilter;
import Server.http.filters.ErrorTrackingFilter;
import Server.http.filters.RateLimitingFilter;
import Server.http.filters.SecurityHeadersFilter;
import com.sun.net.httpserver.Filter;

public final class ApiFilters {

    private static final RateLimiter RATE_LIMITER = RateLimiter.fromEnv();
    private static final Filter RATE_LIMITING_FILTER = new RateLimitingFilter(RATE_LIMITER);
    private static final Filter SECURITY_HEADERS_FILTER = new SecurityHeadersFilter();
    private static final Filter CORRELATION_ID_FILTER = new CorrelationIdFilter();
    private static final Filter ERROR_TRACKING_FILTER = new ErrorTrackingFilter();

    private ApiFilters() {}

    public static Filter rateLimiting() {
        return RATE_LIMITING_FILTER;
    }
    
    public static Filter securityHeaders() {
        return SECURITY_HEADERS_FILTER;
    }
    
    public static Filter correlationId() {
        return CORRELATION_ID_FILTER;
    }
    
    public static Filter errorTracking() {
        return ERROR_TRACKING_FILTER;
    }
    
    /**
     * Get all API filters in the correct order.
     * Order: CorrelationId -> ErrorTracking -> SecurityHeaders -> RateLimiting
     */
    public static java.util.List<Filter> getAllApiFilters() {
        return java.util.List.of(
            CORRELATION_ID_FILTER,
            ERROR_TRACKING_FILTER,
            SECURITY_HEADERS_FILTER,
            RATE_LIMITING_FILTER
        );
    }
}

