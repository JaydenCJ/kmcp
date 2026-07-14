# android-capabilities sample

Reference implementation showing how an Android app can expose selected
system capabilities (contacts search, upcoming calendar events, posting a
notification) as MCP tools with kmcp, gated behind Android runtime
permissions.

**Status: source-only reference. This module is intentionally not wired into
the root Gradle build and has not been compiled** — building it requires the
Android Gradle Plugin, an installed Android SDK, and a device or emulator.
Treat it as a starting point to copy into your own project, not as a
ready-made app.

## What it demonstrates

- `PermissionGate` — a small abstraction over Android runtime permission
  checks, so tool handlers stay testable on the JVM.
- `CapabilityServer` — builds a kmcp `McpServer` whose tools check the gate
  first and return an in-band tool error (`isError: true`) when the user has
  not granted the corresponding permission. The MCP client sees a clear
  message instead of a crash or a silent data leak.
- `LoopbackHttpServer` — a minimal HTTP/1.1 listener on `127.0.0.1` built on
  `java.net.ServerSocket` (Android has no `com.sun.net.httpserver`), serving
  the MCP endpoint and the `/.well-known/mcp` discovery document.
- Provider interfaces (`ContactsProvider`, `CalendarProvider`, `Notifier`)
  with `android.content.ContentResolver`-backed implementations, so the MCP
  layer never touches Android APIs directly.

## Security model

- The server binds `127.0.0.1` only; nothing is reachable from the network.
- Every tool checks its permission at call time; revoking a permission in
  system settings immediately disables the tool's data path.
- No data leaves the device: the MCP client (an on-device agent app or a
  debugging bridge) talks to the loopback interface.

## Build requirements (when you adopt it)

- Android Studio with AGP 8.x, compileSdk 35, minSdk 26.
- Add `dev.kmcp:kmcp:0.1.0` from mavenLocal or your repository.
- Declare the permissions from `src/main/AndroidManifest.xml` and request
  them at runtime before starting `CapabilityService`.
