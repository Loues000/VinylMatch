package Server.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void tokenBucketAllowsBurstThenLimits() {
        RateLimiter limiter = new RateLimiter(2, 60); // 2 burst, 1 token/sec refill

        assertTrue(limiter.tryAcquire("k").allowed());
        assertTrue(limiter.tryAcquire("k").allowed());

        RateLimiter.Result third = limiter.tryAcquire("k");
        assertFalse(third.allowed());
        assertTrue(third.retryAfterSeconds() >= 1);
    }
}

