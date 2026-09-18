# Changelog

All notable changes to this project are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is
[SemVer](https://semver.org/spec/v2.0.0.html).

The percept wire format carries its own `schema` integer, bumped independently of
the app version whenever the shape changes in a way that breaks receivers.

## [Unreleased]

No wire-format change: `schema` stays at 2.

### Fixed

- **`PlaceMemory` could not recognise a place twice.** A place on its first
  visit fails its own persistence filter, so its coverage was zero against every
  scan and it never received a second observation — every scan minted a new
  place. A place is now *learning* for its first six visits: no persistence
  filter, a lower recognition bar, and every recognition teaches it. Settled
  places are unaffected. See [docs/VALIDATION.md](docs/VALIDATION.md).
- `VisionSensor.bind()` used `kotlinx.coroutines.tasks.await` on a
  `ListenableFuture`. Now uses CameraX's own `awaitInstance`.
- Kotlin 2.x build DSL: `jvmTarget` moved to `compilerOptions`.
- `TempoSensorTest` passed `Int` expressions to `assertEquals`'s `Double`
  overload, so the test sources did not compile.

### Changed

- Lifecycle pinned to 2.10.0. The 2.11.0 family pulls
  `lifecycle-runtime-compose-android` in transitively, which demands
  compileSdk 37 and AGP 9.1.
- **AGP 9.4.1, Gradle 9.7.1, compileSdk 37.** The AGP 8.x ceiling had four
  artifacts pinned below their current releases, so it came down. AGP 9
  compiles Kotlin itself, so `org.jetbrains.kotlin.android` is gone; it ships
  Kotlin 2.2.10, so the root `buildscript` raises that to the 2.4.20 this
  project uses. `targetSdk` stays at 36 deliberately.
- `core-ktx` 1.19.0, `lifecycle` 2.11.0, Compose BOM 2026.09.00, okhttp 5.5.0 —
  all four were waiting on compileSdk 37.
- `gradle/actions` v6 with `cache-provider: 'basic'`. v6 defaults to a
  proprietary caching component whose use means accepting Gradle's commercial
  terms; `basic` is the open-source path.
- MediaPipe `tasks-audio` 0.10.35 to 1.0.0. Verified rather than assumed: it
  ships the same classes, and everything `AudioSensor.kt` uses is present in
  the `tasks-core` 1.0.0 it depends on.
- CI actions: `checkout` v7, `setup-java` v6, `setup-python` v7,
  `upload-artifact` v7. All are Node 24 runtimes; every input this workflow
  passes still exists at those versions.

### Not changed, deliberately

- **`targetSdk` stays at 36** while compileSdk moves to 37. The first changes
  what the app compiles against; the second changes how Android behaves
  towards it at runtime, and nothing here has run on a phone yet.
- **Kotlin stays at 2.4.20** rather than falling back to the 2.2.10 that AGP 9
  supplies.

### Added

- `tools/transitive_sweep.py` — resolves the whole runtime dependency graph the
  way Gradle does and reads each AAR's `aar-metadata.properties`, so a
  `checkDebugAarMetadata` rejection can be predicted locally rather than
  discovered in CI. Pure stdlib.
- CI now proves what it previously only attempted: a debug APK is assembled and
  25 unit tests run on every push.

## [0.2.0] — 2026-09-18

Percept schema **1 → 2**. The shape changed; a v0.1 receiver will not parse it.

### Added

- **Radio sense** (`RadioSensor.kt`) — BLE scanning with device counts and named
  devices, WiFi BSSID fingerprinting, registered cell info. MACs and BSSIDs are
  hashed before leaving the device.
- **Place learning** (`PlaceMemory.kt`) — on-device clustering of radio
  fingerprints into persistent, nameable places.
- **Interoception** (`SelfSensor.kt`) — thermal status, thermal headroom, battery
  temperature, current draw, voltage, memory and storage pressure.
- **Full proprioception** (`BodySensor.kt`) — posture from the gravity vector,
  gyroscope RMS, hardware step counter, proximity, magnetic-field anomaly against
  a slow baseline, barometric rate-of-change.
- **Circadian sense** (`TempoSensor.kt`) — offline solar elevation, sunrise and
  sunset by bisection, day length, part of day.
- **Two-way link** (`Link.kt`) — WebSocket transport with eleven inbound commands,
  reconnect backoff, and a `hello` handshake advertising the command vocabulary.
- **Voice** (`Voice.kt`) — TTS out, so the box can answer in the room.
- `tools/receiver.py` rewritten as a stdlib WebSocket server with an interactive
  command console.

### Changed

- **Attention model replaced.** `NoveltyGate` (fixed threshold against the last
  posted percept) is gone; `Habituation.kt` gives every stimulus token a
  persistent trace that saturates with exposure and recovers with time away, plus
  dishabituation and intensity floors.
- Percept restructured into six named sense blocks — `vision`, `hearing`, `radio`,
  `body`, `self`, `tempo` — with a new `attention` block reporting salience,
  novel tokens and any floor reason.
- `Narrator` split into its own file and extended to weave place, interoception
  and time into the prose.
- `Uplink.kt` split: transport moved to `Link.kt`, disk queue to `Spool.kt`.
- Default heartbeat raised 60 s → 120 s, since habituation now handles repetition.
- Motion classification uses gyroscope as well as accelerometer, which separates
  a phone being turned over from a phone in a moving vehicle.

### Fixed

- **Solar elevation double-counted time of day**, returning identical values for
  midnight and noon. The fractional-day term already carries time of day, so the
  separate hour term was wrong. Verified against almanac values.
- **Place matching fragmented badly.** Symmetric Tanimoto similarity split one
  room into many whenever anchors dropped out of a scan, and failed almost
  completely in BLE-only mode because randomised MACs swamped the comparison.
  Replaced with asymmetric persistence-weighted coverage. See
  [docs/VALIDATION.md](docs/VALIDATION.md).
- `ImageProxy.getImage()` opt-in marker was being propagated to callers rather
  than opted into, which would not have compiled.
- `<queries>` was nested inside `<application>` instead of `<manifest>`, silently
  breaking TTS and speech-recogniser resolution on Android 11+.

## [0.1.0] — 2026-09-18

Initial draft.

### Added

- Vision via CameraX and bundled ML Kit models: image labelling, object detection
  with stable track IDs, Latin-script OCR.
- Hearing via `AudioRecord` with MediaPipe YAMNet classification, degrading to
  loudness and peak when the model asset is absent.
- Offline speech transcription via `SpeechRecognizer`, gated on the classifier
  detecting a voice.
- Ambient and motion sensing: lux, accelerometer RMS, compass heading, barometer,
  battery, network, screen state.
- Deterministic on-device `narration` string.
- Novelty gate comparing label and sound-event sets against the last posted
  percept.
- HTTP POST uplink with a bounded disk spool and backlog flush.
- Foreground service with Android 14 service types and a persistent notification.

[Unreleased]: https://github.com/Tubifix77/synth-senses/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Tubifix77/synth-senses/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Tubifix77/synth-senses/releases/tag/v0.1.0
