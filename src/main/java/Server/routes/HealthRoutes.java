package Server.routes;

import Server.http.ApiFilters;
import Server.http.HttpUtils;
import Server.session.RedisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.Config;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Health check endpoint for monitoring and load balancers.
 * Provides detailed status of all dependencies and system resources.
 */
public class HealthRoutes {
    
    private static final Logger log = LoggerFactory.getLogger(HealthRoutes.class);
    private static final ObjectMapper mapper = HttpUtils.getMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    
    private final String spotifyClientId;
    private final String discogsToken;
    
    public HealthRoutes() {
        this.spotifyClientId = Config.getSpotifyClientId();
        this.discogsToken = Config.getDiscogsToken();
    }
    
    public void register(HttpServer server) {
        server.createContext("/api/health", this::handleHealth).getFilters().add(ApiFilters.securityHeaders());
        server.createContext("/api/health/simple", this::handleSimpleHealth).getFilters().add(ApiFilters.securityHeaders());
    }
    
    /**
     * Simple health check for load balancers - just returns 200 OK.
     */
    private void handleSimpleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
            return;
        }
        
        Map<String, String> status = Map.of("status", "UP");
        HttpUtils.sendJson(exchange, 200, status);
    }
    
    /**
     * Comprehensive health check with detailed status.
     */
    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtils.sendApiError(exchange, 405, "method_not_allowed", "Only GET is supported");
            return;
        }
        
        long startTime = System.currentTimeMillis();
        Map<String, Object> health = new HashMap<>();
        Map<String, Object> checks = new HashMap<>();
        
        // Overall status
        boolean allHealthy = true;
        
        // Check Redis
        Map<String, Object> redisCheck = checkRedis();
        checks.put("redis", redisCheck);
        allHealthy &= (Boolean) redisCheck.get("healthy");
        
        // Check Spotify API
        Map<String, Object> spotifyCheck = checkSpotify();
        checks.put("spotify", spotifyCheck);
        allHealthy &= (Boolean) spotifyCheck.get("healthy");
        
        // Check Discogs API
        Map<String, Object> discogsCheck = checkDiscogs();
        checks.put("discogs", discogsCheck);
        allHealthy &= (Boolean) discogsCheck.get("healthy");
        
        // Check system resources
        Map<String, Object> systemCheck = checkSystem();
        checks.put("system", systemCheck);
        allHealthy &= (Boolean) systemCheck.get("healthy");
        
        // Build response
        health.put("status", allHealthy ? "UP" : "DEGRADED");
        health.put("timestamp", Instant.now().toString());
        health.put("checks", checks);
        health.put("responseTimeMs", System.currentTimeMillis() - startTime);
        
        int statusCode = allHealthy ? 200 : 503;
        HttpUtils.sendJson(exchange, statusCode, health);
    }
    
    private Map<String, Object> checkRedis() {
        Map<String, Object> result = new HashMap<>();
        try {
            if (RedisConfig.isAvailable()) {
                result.put("healthy", true);
                result.put("status", "connected");
            } else {
                result.put("healthy", false);
                result.put("status", "disconnected");
                result.put("message", "Redis not configured or unavailable");
            }
        } catch (Exception e) {
            result.put("healthy", false);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }
    
    private Map<String, Object> checkSpotify() {
        Map<String, Object> result = new HashMap<>();
        try {
            if (spotifyClientId == null || spotifyClientId.isBlank()) {
                result.put("healthy", false);
                result.put("status", "not_configured");
                result.put("message", "SPOTIFY_CLIENT_ID not set");
            } else {
                // Try to reach Spotify's token endpoint (lightweight check)
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://accounts.spotify.com/.well-known/openid-configuration"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                boolean reachable = response.statusCode() == 200;
                
                result.put("healthy", reachable);
                result.put("status", reachable ? "reachable" : "unreachable");
                result.put("httpStatus", response.statusCode());
            }
        } catch (Exception e) {
            result.put("healthy", false);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }
    
    private Map<String, Object> checkDiscogs() {
        Map<String, Object> result = new HashMap<>();
        try {
            if (discogsToken == null || discogsToken.isBlank()) {
                result.put("healthy", false);
                result.put("status", "not_configured");
                result.put("message", "DISCOGS_TOKEN not set (optional but recommended)");
            } else {
                // Try to reach Discogs API
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.discogs.com/"))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "VinylMatch/1.0")
                    .GET()
                    .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                boolean reachable = response.statusCode() == 200;
                
                result.put("healthy", reachable);
                result.put("status", reachable ? "reachable" : "unreachable");
                result.put("httpStatus", response.statusCode());
            }
        } catch (Exception e) {
            result.put("healthy", false);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }
    
    private Map<String, Object> checkSystem() {
        Map<String, Object> result = new HashMap<>();
        try {
            MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
            MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
            
            long heapUsed = heapUsage.getUsed();
            long heapMax = heapUsage.getMax();
            double heapUsagePercent = heapMax > 0 ? (double) heapUsed / heapMax * 100 : 0;
            
            // Check disk space
            Path currentDir = Paths.get(".").toAbsolutePath().normalize();
            FileStore fileStore = Files.getFileStore(currentDir);
            long diskTotal = fileStore.getTotalSpace();
            long diskUsable = fileStore.getUsableSpace();
            double diskUsagePercent = diskTotal > 0 ? (double) (diskTotal - diskUsable) / diskTotal * 100 : 0;
            
            boolean healthy = heapUsagePercent < 90 && diskUsagePercent < 95;
            
            Map<String, Object> memory = new HashMap<>();
            memory.put("heapUsedMB", heapUsed / 1024 / 1024);
            memory.put("heapMaxMB", heapMax / 1024 / 1024);
            memory.put("heapUsagePercent", Math.round(heapUsagePercent * 100.0) / 100.0);
            memory.put("nonHeapUsedMB", nonHeapUsage.getUsed() / 1024 / 1024);
            
            Map<String, Object> disk = new HashMap<>();
            disk.put("totalGB", diskTotal / 1024 / 1024 / 1024);
            disk.put("usableGB", diskUsable / 1024 / 1024 / 1024);
            disk.put("usagePercent", Math.round(diskUsagePercent * 100.0) / 100.0);
            
            result.put("healthy", healthy);
            result.put("memory", memory);
            result.put("disk", disk);
            
        } catch (Exception e) {
            result.put("healthy", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
}
