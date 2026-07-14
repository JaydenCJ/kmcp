# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-07-08

### Added

- JSON-RPC 2.0 message model (requests, notifications, responses, error
  responses; string/number/null ids) with strict decode validation and
  standard + MCP error codes.
- MCP domain types in `commonMain`: initialize handshake, capabilities,
  tools, resources, prompts, and polymorphic content blocks
  (text/image/audio/embedded resource).
- Protocol version negotiation for `2025-06-18` and `2025-03-26`, exposed
  through `McpProtocol` and enforced by both client and dispatcher.
- `McpClient` session with a handshake state machine
  (IDLE/INITIALIZING/READY/FAILED/CLOSED) and typed request methods,
  including pagination cursors for `tools/list`, `resources/list`, and
  `prompts/list` (`nextCursor` from a page is passed back as
  `params.cursor`).
- `mcpServer` DSL: tool registration with a JSON Schema builder, resources,
  prompts, instructions; stateless dispatcher with in-band tool error
  reporting.
- JSON Schema subset validator (type/required/properties/enum/const/items/
  ranges/lengths/pattern/additionalProperties) used to reject invalid tool
  arguments with error `-32602`.
- Stateless Streamable HTTP client transport with pluggable
  `HttpTransportEngine`, buffered SSE response parsing, and automatic
  `MCP-Protocol-Version` header handling.
- `.well-known/mcp` discovery document model, parser, and URL helper.
- JVM: `JdkHttpTransportEngine` (`java.net.http`), `KmcpHttpServer` host on
  `com.sun.net.httpserver` (binds `127.0.0.1` by default, Origin allowlist,
  protocol version header check), and a runnable echo server sample
  (`./gradlew runEchoServer`).
- `InMemoryTransport` for tests and in-process client/server wiring.
- Android reference sample `samples/android-capabilities/` (source-only):
  permission-gated contacts/calendar/notification MCP tools.
- Test suite: 77 JVM tests covering serialization round trips, the
  handshake state machine (including the initialize wire format), client
  pagination, dispatcher behavior, schema validation, SSE parsing,
  discovery parsing, and an HTTP integration round trip on `127.0.0.1`.

### Fixed

- `initialize` requests now always serialize the spec-required
  `capabilities` member (previously dropped by `encodeDefaults = false`
  when the client used default capabilities, which strict servers such as
  the official TypeScript SDK reject); guarded by a wire-format
  regression test.
- `KmcpHttpServer.stop()` shuts down the worker thread pool created by
  `start()`, so embedding applications no longer leak non-daemon threads.
- `KmcpHttpServer` answers 404 for paths that merely share a prefix with
  the MCP endpoint or the discovery document (the JDK `HttpServer`
  matches contexts by prefix).
- Android sample `LoopbackHttpServer` reads request bodies as bytes
  instead of characters (multi-byte UTF-8 bodies such as Japanese text no
  longer hang the connection) and applies a socket read timeout.
- Android sample `AndroidPermissionGate` treats `POST_NOTIFICATIONS` as
  granted below API 33, where it is not a runtime permission.
- `scripts/smoke.sh` fails with a non-zero exit when the offline protocol
  round trip cannot run instead of reporting success on structural file
  checks alone.

