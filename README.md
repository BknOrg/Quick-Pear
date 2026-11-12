# QuickPear

QuickPear transfers files and text between Android and desktop devices using Kotlin Multiplatform and Compose. Devices connect directly over LAN, through an optional relay, or through MQTT when a direct connection is unavailable. Browser guests can download explicitly shared files using a temporary Web Share link.

## Modules

- `core`: discovery, authenticated sessions, encrypted framing, transfer validation, resumable storage, MQTT, relay, and Web Share.
- `app/shared`: Compose screens, dialogs, and application state. Device screens, settings, and dialogs live in separate files.
- `app/androidApp`: Android service, notifications, BLE/Wi-Fi integration, private identity storage, and MediaStore download publishing.
- `app/desktopApp`: desktop entry point, tray integration, and installers.
- `server`: the original Ktor sample HTTP endpoint. The transfer relay is `StandaloneRelayServer` in `core`, not this sample endpoint.

## Build and run

Use the Gradle wrapper with JDK 17 or the configured Gradle JVM toolchain. Android builds require SDK platform 37 and the build tools selected by AGP. Set the SDK location in your untracked `local.properties`.

```sh
./gradlew :app:androidApp:assembleDebug
./gradlew :app:desktopApp:run
./gradlew :server:run
```

On Windows, use `gradlew.bat`. The debug APK is written to `app/androidApp/build/outputs/apk/debug/`. Shared Android/desktop release versions are defined by `quickpear.versionName` and `quickpear.versionCode` in `gradle.properties`.

## Tests

The default suite is deterministic and does not connect to a public MQTT broker. It uses an in-process broker for cloud behavior and loopback sockets for LAN, Web Share, and relay behavior.

```sh
./gradlew :core:jvmTest :app:shared:jvmTest :server:test
./gradlew :app:androidApp:assembleDebug :app:desktopApp:classes
```

Test reports are under each module's `build/reports/tests/`. Tests that intentionally contact public brokers are separate and opt-in:

```sh
./gradlew :core:liveIntegrationTest
```

Live tests require internet access and outbound TLS MQTT connectivity. They may fail when a public service is unavailable; they are not a substitute for deterministic checks. Pull requests and releases run the `Checks` workflow before release packaging.

## Security model and protocol migration

Both devices must run protocol v2. There is no legacy plaintext fallback. Version 1 peers must be upgraded; an authentication error tells the user to update both devices when necessary.

A single frame protocol is shared by LAN, MQTT, and relay connections. Each session uses fresh ECDH P-256 keys, an ECDSA identity signature over the full handshake transcript, HKDF-SHA256 directional key derivation, and AES-256-GCM records. The transcript binds the version, purpose, names, identity and ephemeral public keys, nonces, recipient, and MQTT routing session. Directional counters reject replay and out-of-order records. Metadata, text, presence, hotspot credentials, and file chunks are encrypted after authentication.

Both peers commit to their fresh handshake contribution before revealing it, preventing adaptive selection of matching SAS values. Users compare a fresh six-digit SAS on both devices when pairing; both must confirm. The cloud rendezvous code locates a pairing session and is not an authentication credential. Established trusted-device sessions pin the expected identity. Cloud application callbacks run only after proof of identity; an untrusted file sender still requires approval.

MQTT uses TLS with normal certificate validation and the broker hostname. There is no plaintext MQTT fallback. Callers can inject a configured `MqttClient(hosts = ..., port = ...)` through `SignalingClient.mqttClient`. Desktop defaults can also be configured with JVM properties `quickpear.mqtt.hosts` (comma-separated) and `quickpear.mqtt.port` (default 8883). Devices must reach the same broker for message exchange. Broker operators still observe connection timing, routing topics, and traffic volume; pairing handshake identities are public.

Identity and trusted-device files are preserved on upgrade. Identity key pairs are validated when loaded and persisted atomically. Desktop identity files use an owner-only ACL/permissions. Android migrates the identity from `filesDir` to `noBackupFilesDir`. These are filesystem protections, not hardware-backed key storage. Trust changes are serialized and atomically persisted.

Security events contain constant event codes, not ciphertext, message text, private keys, tokens, hotspot passwords, or exception strings.

### Limits

- Handshake: 8 KiB; metadata: 1 MiB; binary chunk: 4 MiB; MQTT packet: 1 MiB; cloud file chunk: 64 KiB.
- Maximum 1,000 files per request and 1,000,000 chunks per file. Frame lengths, fixed payload sizes, file sizes, chunk ranges, duplicate IDs, and arithmetic are checked before use.
- Handshake/ACK timeout: 30 seconds; pairing confirmation: 120 seconds; receive inactivity: 10 minutes.
- Cloud session capacity: 16 per node. Relay registration/pairing timeout: 30 seconds, at most 128 sessions, complementary roles, no third registrant.

## Resume and file storage

Temporary files are kept in `.quickpear-partials-v2` under the download staging directory. Their names are derived from authenticated sender, transfer session, and file ID, rather than the display filename. Resume metadata includes the complete file descriptor, sender identity, and received chunk IDs. The application derives a stable transfer session ID from the recipient and manifest, allowing a retry after restart to reuse valid parts.

A changed sender, filename, size, relative path, chunk size, or SHA-256 invalidates the existing partial. Chunk writes and metadata persistence finish before ACK. Duplicate chunks do not increase progress. Connection failures preserve partials; explicit cancellation and final integrity failures remove the affected partial. All transports require complete chunks, exact size, and the expected SHA-256 before reporting success. Destination names are made unique rather than overwritten.

Recognizable v1 `.part` files with matching metadata sidecars are removed once during migration. Unrecognized user files are left untouched. Android stages downloads in app-specific storage and publishes completed, verified files to `Downloads/Quick Pear` using MediaStore; pending writes are hidden until complete.

## Web Share

Web Share is local HTTP, not an encrypted channel. Use it on a network you trust. Each session creates a random 256-bit capability token in the QR URL; anyone with that link can download the selected files. Tokenless paths are rejected.

Links expire after 15 minutes, rotate when sharing restarts, and are revoked when sharing stops. Stopping or expiry closes active sockets. The server accepts at most eight concurrent clients, limits request headers to 16 KiB, and applies a 15-second request/write-stall timeout. Responses use `Cache-Control: no-store` and `Referrer-Policy: no-referrer`. Do not publish the QR URL or put it in logs.

## Release signing

Android release builds never use the debug keystore. Supply all of:

- `QUICKPEAR_KEYSTORE_PATH`: path to the existing production keystore.
- `QUICKPEAR_KEYSTORE_PASSWORD`
- `QUICKPEAR_KEY_ALIAS`
- `QUICKPEAR_KEY_PASSWORD`

```sh
./gradlew :app:androidApp:assembleRelease
./gradlew :app:desktopApp:packageMsi :app:desktopApp:packageExe
```

The release workflow additionally reads `QUICKPEAR_KEYSTORE_BASE64` from repository secrets, decodes it into the runner temporary directory, and removes it after packaging. Missing credentials fail release validation with an explicit message. Never commit keystores or credentials. Existing debug-signed installations cannot receive an in-place update signed by a different production key; preserve any needed received files before reinstalling. Do not replace an existing production signing identity.

This change prepares the release workflow; it does not publish a release or deploy a relay.

## Troubleshooting and device acceptance

- **Authentication/update error:** upgrade both devices; verify the SAS during new pairing. Do not bypass identity checks.
- **Cloud peer offline:** verify connectivity to the same TLS broker, port 8883, firewall settings, and peer trust. There is no public unencrypted presence feed.
- **Transfer interrupted:** retry the same manifest to resume. Changes to file contents or chunking restart the affected file.
- **Storage failure:** check free space and directory permissions. An unreadable/unwritable destination is treated as a failure.
- **Web Share unavailable:** start a new share and scan its current QR; old URLs expire or are revoked.
- **Release build fails:** configure the production signing variables; debug builds remain available without them.

Before distribution, test on a real Android device and Windows desktop: pair with matching SAS, reject mismatched/rejected pairing, transfer single/multiple/empty files over LAN and cloud, interrupt and restart a transfer, send same-named files concurrently, verify received hashes and Android Downloads visibility, test an old client, and confirm Web Share token revocation/expiry. Automated loopback and in-process cloud tests do not establish real-device interoperability or external broker availability.
