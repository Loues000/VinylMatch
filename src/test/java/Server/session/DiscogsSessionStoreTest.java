package Server.session;

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

import static org.junit.jupiter.api.Assertions.*;

class DiscogsSessionStoreTest {

    @Test
    void createGetAndDestroySession() {
        DiscogsSessionStore store = new DiscogsSessionStore();

        FakeExchange create = new FakeExchange("POST", URI.create("http://127.0.0.1/api/discogs/login"));
        DiscogsSession session = store.createSession(create, "token", "ua", "user1", "User One");
        assertNotNull(session);
        assertNotNull(session.sessionId());
        assertEquals("user1", session.username());
        assertNotNull(create.getResponseHeaders().getFirst("Set-Cookie"));

        FakeExchange followUp = new FakeExchange("GET", URI.create("http://127.0.0.1/api/discogs/status"));
        followUp.getRequestHeaders().add("Cookie", "discogs_session=" + session.sessionId());
        assertNotNull(store.getSession(followUp));

        store.destroySession(followUp);
        assertNull(store.getSession(followUp));
        assertNotNull(followUp.getResponseHeaders().getFirst("Set-Cookie"));
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final URI uri;
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
        @Override public void sendResponseHeaders(int rCode, long responseLength) {}
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1234); }
        @Override public int getResponseCode() { return 200; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
    }
}

