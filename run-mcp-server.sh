#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "Updating current git branch..."
git fetch --prune origin

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$CURRENT_BRANCH" = "HEAD" ]; then
  TARGET_REF="${AMIIBO_GIT_REF:-origin/main}"
else
  TARGET_REF="${AMIIBO_GIT_REF:-origin/$CURRENT_BRANCH}"
fi

git rev-parse --verify "$TARGET_REF" >/dev/null
git reset --hard "$TARGET_REF"
git clean -fd

if [ "${AMIIBO_REEXECUTED:-0}" != "1" ]; then
  echo "Repository updated. Restarting script from the updated checkout..."
  AMIIBO_REEXECUTED=1 exec sh "$0" "$@"
fi

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
