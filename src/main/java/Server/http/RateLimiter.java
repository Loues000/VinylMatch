package Server.http;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory token bucket rate limiter (per key).
 */
public final class RateLimiter {

    private final int burst;
    private final double refillTokensPerMillis;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int burst, int requestsPerMinute) {
        this.burst = Math.max(1, burst);
        int rpm = Math.max(1, requestsPerMinute);
        this.refillTokensPerMillis = (double) rpm / Duration.ofMinutes(1).toMillis();
    }

    public Result tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(burst));
        return bucket.tryConsume(refillTokensPerMillis, burst);
    }

    public static RateLimiter fromEnv() {
        int perMinute = parseIntEnv("RATE_LIMIT_PER_MINUTE", 240);
        int burst = parseIntEnv("RATE_LIMIT_BURST", Math.max(30, perMinute / 4));
        return new RateLimiter(burst, perMinute);
    }

    private static int parseIntEnv(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public record Result(boolean allowed, int retryAfterSeconds, int remainingTokens) {}

    private static final class Bucket {
        private double tokens;
        private long lastRefillMillis;

        private Bucket(int initialTokens) {
            this.tokens = initialTokens;
            this.lastRefillMillis = System.currentTimeMillis();
        }

        private synchronized Result tryConsume(double refillTokensPerMillis, int capacity) {
            long now = System.currentTimeMillis();
            long elapsed = Math.max(0, now - lastRefillMillis);
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * refillTokensPerMillis);
                lastRefillMillis = now;
            }

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Result(true, 0, (int) Math.floor(tokens));
            }

            double missing = 1.0 - tokens;
            long waitMillis = (long) Math.ceil(missing / refillTokensPerMillis);
            int retryAfterSeconds = (int) Math.max(1, Math.ceil(waitMillis / 1000.0));
            return new Result(false, retryAfterSeconds, 0);
        }
    }
}

