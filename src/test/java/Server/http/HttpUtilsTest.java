package Server.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpUtilsTest {

    @Test
    void parsesQueryParams() {
        Map<String, String> params = HttpUtils.parseQueryParams("a=1&b=two%20words&empty=");
        assertEquals("1", params.get("a"));
        assertEquals("two words", params.get("b"));
        assertEquals("", params.get("empty"));
    }

    @Test
    void validatesDiscogsWebUrls() {
        assertTrue(HttpUtils.isDiscogsWebUrl("https://www.discogs.com/release/1-test"));
        assertFalse(HttpUtils.isDiscogsWebUrl("https://example.com/release/1-test"));
        assertFalse(HttpUtils.isDiscogsWebUrl("javascript:alert(1)"));
    }

    @Test
    void handlesCorsPreflight() throws Exception {
        FakeExchange ex = new FakeExchange("OPTIONS", URI.create("http://127.0.0.1/api/test"));
        ex.getRequestHeaders().add("Origin", "http://127.0.0.1:8888");
        assertTrue(HttpUtils.handleCorsPreflightIfNeeded(ex));
        assertNotNull(ex.getResponseHeaders().getFirst("Access-Control-Allow-Methods"));
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final URI uri;
        private int responseCode;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

        private FakeExchange(String method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) {
            this.responseCode = rCode;
        }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1234); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}

