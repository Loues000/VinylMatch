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

class SpotifySessionStoreTest {

    @Test
    void createGetOrCreateAndDestroySession() {
        SpotifySessionStore store = new SpotifySessionStore();

        FakeExchange create = new FakeExchange("GET", URI.create("http://127.0.0.1/api/auth/status"));
        SpotifySession session = store.createSession(create);
        assertNotNull(session);
        assertNotNull(session.getSessionId());
        assertNotNull(create.getResponseHeaders().getFirst("Set-Cookie"));
        assertNotNull(store.getSessionById(session.getSessionId()));

        FakeExchange followUp = new FakeExchange("GET", URI.create("http://127.0.0.1/api/auth/status"));
        followUp.getRequestHeaders().add("Cookie", "spotify_session=" + session.getSessionId());
        assertSame(session, store.getOrCreateSession(followUp));
        assertSame(session, store.getSession(followUp));

        store.destroySession(followUp);
        assertNull(store.getSessionById(session.getSessionId()));
        assertNull(store.getSession(followUp));
        assertNotNull(followUp.getResponseHeaders().getFirst("Set-Cookie"));
    }

    @Test
    void getSessionReturnsNullWithoutCookie() {
        SpotifySessionStore store = new SpotifySessionStore();
        FakeExchange ex = new FakeExchange("GET", URI.create("http://127.0.0.1/api/auth/status"));
        assertNull(store.getSession(ex));
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

