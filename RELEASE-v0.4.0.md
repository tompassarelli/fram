# v0.4.0

Fram v0.4.0 establishes the source-head production boundary: an
occurrence-native server, binary FRAMLOG persistence, the closed FRAMRPC v1
data plane, and an explicit no-JVM Graal deployment route while the Beagle
Native World server is completed.

This is a minor release because deployment and persisted-data compatibility
change intentionally. Calling it v0.3.9 would incorrectly imply a drop-in
patch for the deployed v0.3 flat-log and EDN-line runtime.

## Highlights

- `bin/fram-server` is native-first and fails closed. The default production
  route requires a completed Beagle native artifact; it never falls back to a
  JVM silently.
- `FRAM_SERVER_RUNTIME=graal` selects a self-contained Graal server when
  `FRAM_GRAAL_ARTIFACT` names its absolute executable path. This is the
  transitional production route, not a Native World artifact or a change to
  the Beagle-first architecture.
- `native/build.sh` separates the fast JVM/AOT preparation phase from Graal
  image generation. The JVM remains the development and differential-oracle
  surface; native-image compilation belongs in release builds.
- The Cloudflare server image now builds a static musl Graal executable
  with GraalVM `25.0.2-muslib` pinned explicitly. The authenticated Babashka
  HTTP/JSON shim remains a separate container and the private server port
  must not be published directly.
- Reachability metadata is checked in and was produced from exercised server
  paths. The image build is multi-stage and the final runtime contains the
  server executable rather than a JVM.
- FRAMRPC v1 remains the closed thirteen-operation binary data plane for the
  CLI, zero-dependency Node client, Cloudflare shim, and server. SpaceId,
  bounded framing, recursive Terms, paging, snapshots, and leases keep the
  same source-head contract.

## Compatibility and migration

- v0.3 flat logs are not served directly. Quiesce the old writer and run the
  one-shot migration into a new binary FRAMLOG before cutover; retain the old
  log as rollback input. The v0.4 server does not emulate the legacy EDN-line
  wire protocol.
- Existing service definitions that relied on an implicit JVM server must now
  choose deliberately: `graal` plus an absolute `FRAM_GRAAL_ARTIFACT`,
  `jvm-oracle`, or checkout-only `jvm-dev`. The default `native` route requires
  an absolute `FRAM_NATIVE_ARTIFACT_DIR` containing a valid `READY` marker and
  `bin/fram-server-native`.
- Reminder for consumers upgrading from before v0.3.7: that release made a
  separate breaking Node-client positional-vocabulary change. Its migration
  remains in force; see the [v0.3.7 release notes](https://github.com/tompassarelli/fram/releases/tag/v0.3.7).
  It did not alter the binary wire, where Triple positions are positional.
- Do not expose FRAMRPC directly to untrusted networks. Authentication and TLS
  remain the responsibility of the gateway or Cloudflare shim.

## Release evidence

- JVM server regression suite passed.
- Native RPC boundary ratchet passed 15/15.
- The Node FRAMRPC integration suite passed 11/11 checks against the Graal
  executable.
- The Cloudflare Worker/JSON-to-Babashka-to-FRAMRPC integration suite passed
  16/16 checks against the Graal executable.
- The static release container accepted a mutation, reported version 1,
  restarted, replayed the binary FRAMLOG, and returned the persisted fact.
- The observed release image was 52,112,401 bytes. Its 64-bit x86 executable
  had no ELF interpreter and `ldd` reported that it was not dynamic.

## Known limitations

- Graal is a deployment bridge. It removes the runtime JVM dependency but does
  not replace the immutable Beagle Native World or complete the direct-native
  cutover.
- Native-image compilation is intentionally slower than the JVM development
  loop. Ordinary development continues on JVM/Babashka paths; Graal builds are
  reserved for release and deployment gates.
- This release makes no generic startup, memory, or throughput guarantee. Those
  properties remain workload- and deployment-specific and must be measured on
  Fram rather than inferred from other Graal applications.
