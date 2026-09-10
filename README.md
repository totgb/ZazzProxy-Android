# ZazzProxy Android

A local-network file sharing prototype for Android that discovers nearby peers over UDP, exchanges manifest metadata, and transfers files securely using AES-GCM encryption. This project is currently an early prototype and is still being shaped by the architecture and planning notes in the `plan/` directory.

## What it does

ZazzProxy is designed to let Android devices on the same LAN:

- discover nearby peers via broadcast announcements
- advertise whether the device is acting as a host/server
- request and share file manifests
- download files from a peer
- accept uploads from a peer
- store shared files in an app-controlled directory
- export/import versioned binary `.zaZzProxy` manifest archives and `.zaZzSettings` settings archives

The app currently exposes this through a simple UI with browsing, hosting, settings, and developer sections.

## Current project status

This repo is not a finished production app. It is best described as a working proof-of-concept for a secure LAN-sharing protocol and Android transport layer.

The project has an explicit roadmap in `plan/Plan.txt` that calls out the next steps:

- define the system scope clearly
- stabilize the networking architecture
- define the protocol and pack format
- harden the transfer path and failure handling
- add real tests around networking and transfers
- document the security model and build/run process
- freeze a v0.1 boundary

That means the implementation here is a functional prototype, not a fully hardened release.

## Architecture snapshot

The app is a single-module Android project using Java and AndroidX.

### Main app module

- `app/build.gradle` defines the Android app configuration
- `app/src/main/AndroidManifest.xml` declares Internet/Wi-Fi permissions and the foreground service
- `app/src/main/java/com/totgb/zazzproxy/` contains the application logic

### Core classes

- `MainActivity.java`  
  Main app shell and UI. Hosts the bottom navigation, settings flow, development panel, and start/stop networking actions.

- `ServerFragment.java`  
  Fragment used when the app is in server/host mode. Lets the user select files to share and lists currently hosted files.

- `ZazzUdpNode.java`  
  The heart of the project. Handles broadcast discovery, encrypted payloads, manifest exchange, downloads/uploads, chunked transfer logic, and file integrity verification.

- `archive/ZazzArchive.java`  
  Import/export layer for the app’s versioned binary archive formats. The files use `zaZzP` and `zaZzS` magic headers, an integer format version, length-prefixed UTF-8 fields, and bounded entry counts.

- `model/`  
  Transport-independent `Peer`, `Manifest`, `ZazzPack`, `FileInfo`, and `Transfer` data structures.

- `security/`  
  `KeyManager`, `KeyDerivation`, AES-GCM `Cipher`, `Integrity`, and constant-time `Authentication` helpers. The UDP node delegates key derivation and packet encryption/decryption to this package.

- `service/`  
  Foreground service implementation plus `ServiceController` for starting and stopping background hosting.

- `ZazzBackgroundService.java`  
  Foreground service that keeps the secure sharing socket alive while the app is in the background.

### Supporting project notes

- `plan/Architecture.txt` outlines the intended architectural layers: networking, data/protocol, security, logging, and testing.
- `plan/Plan.txt` is the project roadmap and design backlog.

## Networking and protocol model

The transport layer is implemented in `ZazzUdpNode` and uses UDP on port `39841`.

### Discovery and announcements

The node periodically broadcasts a HELLO message containing:

- node ID
- node name
- version
- whether it is acting as a server

When a peer is discovered, the app callback is triggered and the app can request a manifest or initiate a transfer.

### Message types

The implementation defines these packet types:

- `HELLO`
- `MANIFEST_REQUEST`
- `MANIFEST`
- `DOWNLOAD_REQUEST`
- `FILE_CHUNK`
- `ACK`
- `UPLOAD_OFFER`
- `UPLOAD_CHUNK`
- `ERROR`

### Security model

The protocol uses:

- AES-GCM encryption
- PBKDF2-derived key material using a shared network key
- SHA-256 validation on file payloads
- integrity checks during transfer completion

The derived key is produced from the user-provided network key and a fixed salt string (`"ZazzProxy/v1"`), then used with AES-GCM for authenticated encryption.

### Transfer behavior

- manifests and control messages are exchanged as encrypted binary records on the live UDP protocol
- file downloads are chunked into packets
- incoming chunks are reassembled into `.part` temporary files
- final files are validated with SHA-256 before rename into place
- acks are used to confirm delivery

## App behavior and UX

The app currently provides a basic user workflow:

1. Go to Settings and set a shared network key
2. Start networking in Browse or Host mode
3. Discover peers on the LAN
4. Request a manifest from a server peer
5. Download a file from the manifest list
6. Host files from the app-controlled share folder in server mode
7. Import/export `.zaZzProxy` and `.zaZzSettings` files

The UI is intentionally lightweight and intended for experimentation and local testing rather than polished end-user deployment.

## File formats

The project exposes two custom archive formats:

- `.zaZzProxy` — versioned binary manifest of shared files
- `.zaZzSettings` — versioned binary saved client/server settings without the network key

The binary archives and live protocol are both binary. Import rejects unknown magic headers and unsupported versions rather than silently accepting malformed or legacy content.

## Offline profile pictures

The app includes bundled offline avatar vectors in `res/drawable` (`avatar_blue`, `avatar_green`, and `avatar_orange`). User-selected pictures can be copied into app-private storage through `settings/ProfileAvatarStore`; no upload or remote profile service is involved.

## Splash animation

Startup is fully programmatic. The ZazzProxy mark jumps from the bottom-left through each screen corner and then toward the center. Tapping the splash skips the animation immediately.

## Programmatic desktop-style UI

The main interface uses a macOS-inspired glass presentation built entirely in Java: gradient surfaces, translucent cards, rounded controls, deterministic waypoint press motion, branded status toasts, and selectable visual themes. Network start/stop transitions provide offline notification tones and matching custom toasts.

These are defined by `ZazzArchive` and validated by unit tests.

## Build and run

### Prerequisites

- Android Studio
- Android SDK with the project’s configured compile SDK level
- Java 11-compatible toolchain

### Build

From the repository root:

```bash
gradle assembleDebug
```

### Run

Open the project in Android Studio and run the `app` module on an emulator or connected Android device.

## Dependencies

The project includes a focused stack for a local secure-sharing prototype:

- Android app libraries: AppCompat, Material, Activity, ConstraintLayout
- networking: Netty
- data/protocol: Jackson
- security: Bouncy Castle
- crypto utility: Apache Commons Codec
- logging: SLF4J
- testing: JUnit, TestNG, Mockito

## Important caveats

This codebase is still in a rapid iteration phase. Some limitations are visible in the implementation:

- the shared key is required and must be set manually
- this is LAN-focused and not a general internet-sharing system
- protocol, trust model, and failure handling are still evolving
- the app is a prototype rather than a locked-down production implementation

## Project structure

```text
ZazzProxy-Android/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/
│       ├── androidTest/
│       ├── main/
│       └── test/
├── gradle/
│   └── wrapper/
├── plan/
│   ├── Architecture.txt
│   └── Plan.txt
├── build.gradle
├── gradle.properties
├── gradlew
├── gradlew.bat
├── settings.gradle
├── local.properties
├── .gitignore
└── README.md
```

## Summary

ZazzProxy Android is a compact Android-side secure peer-to-peer sharing prototype built around UDP discovery and AES-GCM-encrypted payloads. It is an active concept project with a clear roadmap toward a more formal protocol, stronger architecture, and production-level reliability.
