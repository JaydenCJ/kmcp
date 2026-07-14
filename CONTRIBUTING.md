# Contributing to kmcp

Thanks for considering a contribution. This document keeps the workflow
short and predictable.

## Development setup

Requirements: JDK 11 or newer. No Android SDK or macOS is needed for the
core library — all tests run on the JVM target.

```bash
git clone https://github.com/JaydenCJ/kmcp.git
cd kmcp
./gradlew jvmTest        # run the test suite
bash scripts/smoke.sh    # offline MCP protocol round-trip smoke test
```

Apple targets (`iosArm64`, `iosSimulatorArm64`) build only on macOS; the
Gradle build skips them automatically elsewhere.

## Ground rules

- **Every change needs a test.** Protocol behavior (message shapes, error
  codes, handshake transitions) must be pinned by `commonTest` or `jvmTest`
  cases, not just exercised manually.
- **Keep the public API explicit and documented.** The build uses Kotlin
  explicit API mode; every public declaration needs a KDoc comment in
  English.
- **`commonMain` stays platform-free.** No `java.*`, `android.*`, or
  platform-specific imports in the shared core; platform code lives in the
  target source sets behind common interfaces.
- **No new dependencies without discussion.** The core intentionally
  depends only on kotlinx-serialization and kotlinx-coroutines.
- **Spec references welcome.** When changing wire behavior, link the
  relevant section of the MCP specification in the pull request.

## Pull request flow

1. Open or find an issue describing the change.
2. Fork, branch from `main`, and keep the branch focused on one topic.
3. Make sure `./gradlew jvmTest` and `bash scripts/smoke.sh` pass.
4. Open the pull request with a short description of the behavior change
   and the tests that cover it.

## Reporting issues

Include the kmcp version, the platform (JVM/Android/iOS), a minimal
reproduction (ideally an `InMemoryTransport`-based snippet), and the
observed vs. expected JSON-RPC payloads if the issue is protocol-level.
