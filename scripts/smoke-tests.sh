#!/bin/bash
# Smoke tests for VinylMatch staging environment

set -e

BASE_URL="${BASE_URL:-http://localhost:8888}"
FAILED=0

echo "🧪 Running smoke tests against $BASE_URL..."

# Test 1: Health endpoint
echo "  Testing /api/health..."
if curl -sf "$BASE_URL/api/health" > /dev/null; then
    echo "    ✅ Health endpoint responding"
else
    echo "    ❌ Health endpoint failed"
    FAILED=1
fi

# Test 2: Auth status endpoint
echo "  Testing /api/auth/status..."
if curl -sf "$BASE_URL/api/auth/status" > /dev/null; then
    echo "    ✅ Auth status endpoint responding"
else
    echo "    ❌ Auth status endpoint failed"
    FAILED=1
fi

# Test 3: Static files
echo "  Testing static files..."
if curl -sf "$BASE_URL/" > /dev/null; then
    echo "    ✅ Static files serving"
else
    echo "    ❌ Static files not serving"
    FAILED=1
fi

# Test 4: Config endpoint
echo "  Testing /api/config/vendors..."
if curl -sf "$BASE_URL/api/config/vendors" > /dev/null; then
    echo "    ✅ Config endpoint responding"
else
    echo "    ❌ Config endpoint failed"
    FAILED=1
fi

if [ $FAILED -eq 0 ]; then
    echo ""
    echo "✅ All smoke tests passed!"
    exit 0
else
    echo ""
    echo "❌ Some smoke tests failed!"
    exit 1
fi
