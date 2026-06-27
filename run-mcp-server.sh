#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "Updating current git branch..."
git pull --ff-only

echo "Ensuring Gradle wrapper is executable..."
chmod +x ./gradlew

echo "Building MCP server jar..."
./gradlew jar

echo "Starting Amiibo MCP server..."
AMIIBO_TRANSPORT=streamable-http \
AMIIBO_HTTP_HOST=0.0.0.0 \
AMIIBO_HTTP_PORT=8080 \
AMIIBO_ALLOWED_HOSTS=localhost,127.0.0.1,5.35.125.50 \
java -jar build/libs/amiibo-mcp-0.1.0.jar
