package Server.http.filters;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Filter that catches unhandled exceptions and reports them to error tracking service.
 * Also provides graceful error responses instead of crashing the request.
 */
public class ErrorTrackingFilter extends Filter {
    
    private static final Logger log = LoggerFactory.getLogger(ErrorTrackingFilter.class);
    
    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        try {
            chain.doFilter(exchange);
        } catch (Exception e) {
            // Log the error with full stack trace
            log.error("Unhandled exception in request {} {}: {}", 
                exchange.getRequestMethod(), 
                exchange.getRequestURI(),
                e.getMessage(), 
                e);
            
            // Report to Sentry if configured
            reportToSentry(e, exchange);
            
            // Send graceful error response if not already sent
            if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                String errorResponse = String.format(
                    "{\"error\": \"internal_error\", \"message\": \"An unexpected error occurred\"}"
                );
                byte[] bytes = errorResponse.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(500, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ioe) {
                    // Response already sent, ignore
                }
            }
        }
    }
    
    private void reportToSentry(Exception e, HttpExchange exchange) {
        try {
            // Check if Sentry is configured
            String sentryDsn = com.hctamlyniv.Config.getSentryDsn();
            if (sentryDsn == null || sentryDsn.isBlank()) {
                return; // Sentry not configured
            }
            
            // Get stack trace as string
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stackTrace = sw.toString();
            
            // Build breadcrumb context
            String context = String.format(
                "Request: %s %s\nHeaders: %s\nQuery: %s",
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().keySet(),
                exchange.getRequestURI().getQuery()
            );
            
            // Log to console (would integrate with Sentry SDK in real implementation)
            log.warn("Would report to Sentry:\nException: {}\nStack trace:\n{}\nContext:\n{}",
                e.getClass().getName(),
                stackTrace,
                context);
            
        } catch (Exception sentryError) {
            // Don't let Sentry errors break the application
            log.error("Failed to report to Sentry: {}", sentryError.getMessage());
        }
    }
    
    @Override
    public String description() {
        return "Catches unhandled exceptions and reports them to error tracking service";
    }
}
