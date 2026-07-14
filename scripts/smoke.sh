#!/usr/bin/env bash
# Smoke test for kmcp.
#
# The substantive assertion is the real MCP protocol round trip
# (initialize -> tools/list -> tools/call over HTTP on 127.0.0.1), run as
# the integration test subset with Gradle in offline mode, so the script
# never touches the network. Structural file checks run first, but they
# are only a precondition: when the round trip cannot run (no usable
# Java, Gradle distribution, or dependency cache), the smoke FAILS with a
# non-zero exit instead of reporting success, because file-existence
# checks alone prove nothing about protocol behavior. Seed the Gradle
# caches (e.g. run ./gradlew jvmTest once) before invoking this script.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() {
    echo "SMOKE FAIL: $*" >&2
    exit 1
}

# --- structural assertions (always run) ---------------------------------
required_files=(
    settings.gradle.kts
    build.gradle.kts
    gradlew
    gradle/wrapper/gradle-wrapper.properties
    src/commonMain/kotlin/dev/kmcp/protocol/JsonRpc.kt
    src/commonMain/kotlin/dev/kmcp/server/McpServer.kt
    src/commonMain/kotlin/dev/kmcp/client/McpClient.kt
    src/commonMain/kotlin/dev/kmcp/schema/SchemaValidator.kt
    src/commonMain/kotlin/dev/kmcp/discovery/WellKnown.kt
    src/jvmMain/kotlin/dev/kmcp/server/jvm/KmcpHttpServer.kt
    src/jvmTest/kotlin/dev/kmcp/integration/HttpTransportIntegrationTest.kt
)
for f in "${required_files[@]}"; do
    [ -f "$f" ] || fail "missing file: $f"
done
grep -q 'rootProject.name = "kmcp"' settings.gradle.kts \
    || fail "settings.gradle.kts is unreadable or has an unexpected project name"
echo "[smoke] structural assertions passed (${#required_files[@]} files)"

# --- protocol round trip via Gradle, strictly offline --------------------
gradle_user_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
wrapper_ready=""
for marker in "$gradle_user_home"/wrapper/dists/gradle-*/*/gradle-*.ok; do
    if [ -e "$marker" ]; then
        wrapper_ready=1
        break
    fi
done
deps_ready=""
if [ -d "$gradle_user_home/caches/modules-2/files-2.1/org.jetbrains.kotlinx" ]; then
    deps_ready=1
fi

command -v java >/dev/null 2>&1 \
    || fail "java is unavailable; the protocol round trip cannot run (structural checks alone are not a pass)"
[ -n "$wrapper_ready" ] \
    || fail "no Gradle distribution under $gradle_user_home/wrapper/dists; run ./gradlew jvmTest once to seed it"
[ -n "$deps_ready" ] \
    || fail "no dependency cache under $gradle_user_home/caches/modules-2; run ./gradlew jvmTest once to seed it"

echo "[smoke] running MCP protocol round-trip tests (offline)"
./gradlew --offline -q jvmTest \
    --tests "dev.kmcp.integration.HttpTransportIntegrationTest" --rerun \
    || fail "protocol round-trip tests failed"
echo "[smoke] initialize -> tools/list -> tools/call over 127.0.0.1 passed"

echo "SMOKE OK"
