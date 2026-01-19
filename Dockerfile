# VinylMatch - Multi-stage Docker Build
# 
# Build: docker build -t vinylmatch .
# Run:   docker run -p 8888:8888 \
#          -e SPOTIFY_CLIENT_ID=xxx \
#          -e SPOTIFY_CLIENT_SECRET=xxx \
#          -e DISCOGS_TOKEN=xxx \
#          vinylmatch

# ============================================================================
# Stage 1: Build
# ============================================================================
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom.xml first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -B

# ============================================================================
# Stage 2: Runtime
# ============================================================================
FROM eclipse-temurin:21-jre

WORKDIR /app

# Create non-root user
RUN groupadd -r vinylmatch && useradd -r -g vinylmatch vinylmatch

# Copy artifacts from builder
COPY --from=builder /app/target/VinylMatch.jar ./VinylMatch.jar
COPY --from=builder /app/src/main/frontend ./src/main/frontend

# Create cache directories
RUN mkdir -p cache/discogs cache/playlists config && \
    chown -R vinylmatch:vinylmatch /app

# Switch to non-root user
USER vinylmatch

# Environment variables (override at runtime)
ENV PORT=8888
ENV PUBLIC_BASE_URL=""
ENV SPOTIFY_CLIENT_ID=""
ENV SPOTIFY_CLIENT_SECRET=""
ENV SPOTIFY_REDIRECT_URI=""
ENV DISCOGS_TOKEN=""
ENV DISCOGS_USER_AGENT="VinylMatch/1.0"
ENV CORS_ALLOWED_ORIGINS=""

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:${PORT}/api/auth/status || exit 1

EXPOSE 8888

# Run the application
CMD ["java", "-jar", "VinylMatch.jar"]
