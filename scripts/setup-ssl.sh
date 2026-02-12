#!/bin/bash
# SSL Certificate Management Script for VinylMatch
# 
# This script automates Let's Encrypt certificate generation and renewal
# using certbot in standalone mode.

set -e

DOMAIN="${DOMAIN:-}"
EMAIL="${EMAIL:-}"
CERT_DIR="${CERT_DIR:-/app/certs}"

if [ -z "$DOMAIN" ] || [ -z "$EMAIL" ]; then
    echo "Usage: DOMAIN=yourdomain.com EMAIL=you@example.com ./scripts/setup-ssl.sh"
    exit 1
fi

echo "🔒 Setting up SSL certificates for $DOMAIN..."

# Install certbot if not present
if ! command -v certbot &> /dev/null; then
    echo "Installing certbot..."
    apt-get update && apt-get install -y certbot
fi

# Generate certificates
if [ ! -d "$CERT_DIR" ]; then
    mkdir -p "$CERT_DIR"
fi

# Request certificate
certbot certonly \
    --standalone \
    --preferred-challenges http \
    --agree-tos \
    --email "$EMAIL" \
    -d "$DOMAIN" \
    --config-dir "$CERT_DIR" \
    --work-dir "$CERT_DIR" \
    --logs-dir "$CERT_DIR/logs"

echo "✅ SSL certificates generated successfully!"
echo "📍 Certificates location: $CERT_DIR/live/$DOMAIN/"
echo ""
echo "To enable auto-renewal, add this to crontab:"
echo "0 12 * * * certbot renew --quiet --config-dir $CERT_DIR --work-dir $CERT_DIR --logs-dir $CERT_DIR/logs"
