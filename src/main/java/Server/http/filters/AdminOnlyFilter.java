package Server.http.filters;

import Server.http.HttpUtils;
import Server.session.SpotifySession;
import Server.session.SpotifySessionStore;
import com.hctamlyniv.Config;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class AdminOnlyFilter extends Filter {

    private static final Logger log = LoggerFactory.getLogger(AdminOnlyFilter.class);
    
    private final SpotifySessionStore sessionStore;

    public AdminOnlyFilter(SpotifySessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        SpotifySession session = sessionStore.getSession(exchange);
        
        if (session == null || !session.isLoggedIn()) {
            log.warn("Admin-only endpoint accessed without login");
            HttpUtils.sendApiError(exchange, 401, "unauthorized", "Authentication required");
            return;
        }
        
        String userId = session.getUserId();
        if (userId == null || userId.isBlank()) {
            log.warn("Admin-only endpoint accessed without user ID in session");
            HttpUtils.sendApiError(exchange, 401, "unauthorized", "User ID not available");
            return;
        }
        
        if (!Config.getAdminUserIds().contains(userId)) {
            log.warn("Admin-only endpoint accessed by non-admin user: {}", userId);
            HttpUtils.sendApiError(exchange, 403, "forbidden", "Admin access required");
            return;
        }
        
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Restricts access to admin users only";
    }
}
