package Server.http.filters;

import Server.http.RateLimiter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitingFilterTest {

    @Test
    void allowsFirstRequestAndLimitsSecond() throws Exception {
        RateLimiter limiter = new RateLimiter(1, 1);
        RateLimitingFilter filter = new RateLimitingFilter(limiter);

        FakeExchange exchange1 = new FakeExchange("GET", URI.create("http://127.0.0.1/api/test"));
        AtomicBoolean called = new AtomicBoolean(false);
        filter.doFilter(exchange1, new com.sun.net.httpserver.Filter.Chain(List.of(), (HttpHandler) ex -> called.set(true)));
        assertTrue(called.get());

        FakeExchange exchange2 = new FakeExchange("GET", URI.create("http://127.0.0.1/api/test"));
        AtomicBoolean called2 = new AtomicBoolean(false);
        filter.doFilter(exchange2, new com.sun.net.httpserver.Filter.Chain(List.of(), (HttpHandler) ex -> called2.set(true)));
        assertFalse(called2.get());
        assertEquals(429, exchange2.responseCode);
        assertNotNull(exchange2.getResponseHeaders().getFirst("Retry-After"));
    }

    @Test
    void doesNotLimitNonApiPaths() throws Exception {
        RateLimiter limiter = new RateLimiter(1, 1);
        RateLimitingFilter filter = new RateLimitingFilter(limiter);
        FakeExchange exchange = new FakeExchange("GET", URI.create("http://127.0.0.1/home.html"));
        AtomicBoolean called = new AtomicBoolean(false);
        filter.doFilter(exchange, new com.sun.net.httpserver.Filter.Chain(List.of(), (HttpHandler) ex -> called.set(true)));
        assertTrue(called.get());
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final URI uri;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private int responseCode;

        private FakeExchange(String method, URI uri) {
            this.method = method;
            this.uri = uri;
            requestHeaders.add("Origin", "http://127.0.0.1:8888");
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { this.responseCode = rCode; }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1234); }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}
