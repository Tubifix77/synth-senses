# Changelog

All notable changes to this project are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is
[SemVer](https://semver.org/spec/v2.0.0.html).

The percept wire format carries its own `schema` integer, bumped independently of
the app version whenever the shape changes in a way that breaks receivers.

## [Unreleased]

### Changed

- **The receiver is composable.** Percepts leave on stdout as one JSON object
  per line and everything written for a person leaves on stderr, so downstream
  is a pipe rather than an edit to `handle_percept()`. JSON lines switch on
  automatically when stdout is not a terminal, so running it bare still gives
  the readable live view; `--jsonl` / `--no-jsonl` force it, `--quiet` and
  `--pretty` control the human rendering.
- The websocket's `"type": "percept"` envelope is stripped before a percept is
  logged or streamed, so every line is a percept regardless of whether it
  arrived over the websocket, in a backlog flush, or by HTTP POST. The JSONL
  log was previously inconsistent about this.
- The command console only starts when stdin is a terminal. Otherwise it would
  consume whatever is being piped in.

### Fixed

- **A rendering error could break the transport.** A percept with no `trigger`
  made the human renderer raise while formatting; the exception escaped to the
  connection handler and the client received no HTTP response at all, which a
  phone reads as a failed POST and re-spools forever. The renderer no longer
  assumes fields are present or well-typed, and `deliver()` isolates it, so
  logging, the stdout stream and the response never depend on the
  pretty-printer.

## [0.3.0] — 2026-09-18

The first version that compiles, and the first whose tests have ever run.

Everything before this was written without an Android SDK to hand, so "it
builds" was an assumption. It is now a fact that CI re-checks on every push,
along with a debug APK, Android Lint, 71 JVM unit tests and two standard
library test suites for the tooling.

No wire-format change: `schema` stays at 2, and a v0.2 receiver will parse
these percepts unchanged.

### Fixed

- **`PlaceMemory` could not recognise a place twice — at all, on any input.**
  A place created moments ago has every anchor at `seen = 1`, the persistence
  filter demands 2, so its expected weight was zero, its coverage against any
  scan was zero, and it never received the second observation that would have
  let it mature. Eighteen visits to one room produced eighteen rooms. The
  metric itself was sound and validated; the prototype trained places by
  calling `observe()` directly and so never exercised the path a phone takes.
  A place is now *learning* for its first six visits: no persistence filter, a
  lower recognition bar to offset the passers-by still inflating its
  denominator, and every recognition teaches it. Settled places are unchanged.
  See [docs/VALIDATION.md](docs/VALIDATION.md).
- `VisionSensor.bind()` awaited a `ListenableFuture` with the Play Services
  `await`, which does not apply to it. Uses CameraX's own `awaitInstance` now.
  This was the only real error in the Kotlin, and it was in code nobody had
  flagged as risky.
- `TempoSensorTest` handed `Int` expressions to the `Double` overload of
  `assertEquals`, so the test sources did not compile.
- `app/src/main/assets/README.md` was being packaged into the APK and shipped
  to devices. Found by opening the artifact CI produces.

### Added

- **Tests for the invariants that had none.** Token bucketing, which the whole
  attention model rests on and whose failure would have been silent; sense
  blocks serialising as explicit nulls rather than fabricated values; the
  FNV-1a identifier hash, pinned to independently computed values; the bounded
  backlog; inbound command parsing; the narrator; and the full wire shape, so
  `docs/PROTOCOL.md` cannot drift from the code unnoticed.
- `tools/transitive_sweep.py` — resolves the whole runtime dependency graph the
  way Gradle does and reads each AAR's `aar-metadata.properties`, so a
  `checkDebugAarMetadata` rejection is answerable in seconds instead of a
  four-minute CI cycle. It reads the ceiling out of the build files, so it
  cannot go stale. Pure stdlib.
- `tools/test_receiver_ws.py` — the WebSocket transport, which is the primary
  one and had never been tested automatically. Speaks RFC 6455 as a real
  client, with the handshake asserted against the spec's own published example.
- `tools/test_transitive_sweep.py` — the sweep's version ordering and ceiling
  detection, since a wrong answer there would be quiet rather than loud.
- `Spool` takes a `File` with a `Context` convenience constructor, and
  `shortHash` is a top-level internal function, both so a plain JVM test can
  reach them. The arrangement `Habituation` and `PlaceMemory` already used.

### Changed

- **AGP 9.4.1, Gradle 9.7.1, compileSdk 37, Kotlin 2.4.20.** Four artifacts had
  reached releases declaring `minCompileSdk=37` and were each pinned below
  their current version by that one wall, with the gap widening on every
  dependency run. AGP 9 compiles Kotlin itself, so `org.jetbrains.kotlin.android`
  is gone; it ships Kotlin 2.2.10, so the root `buildscript` raises that to the
  2.4.20 this project uses.
- Dependencies freed by the move: `core-ktx` 1.19.0, `lifecycle` 2.11.0,
  Compose BOM 2026.09.00, okhttp 5.5.0. The okhttp bump was never an API
  problem, only a metadata one.
- MediaPipe `tasks-audio` 0.10.35 to 1.0.0. Verified rather than assumed: it
  ships the same classes, and everything `AudioSensor.kt` names is present in
  the `tasks-core` 1.0.0 it depends on. Three documents had predicted this
  would be the breakage. It was the cleanest bump of the batch.
- CI actions to `checkout` v7, `setup-java` v6, `setup-python` v7,
  `upload-artifact` v7, and `gradle/actions` v6 with `cache-provider: 'basic'`.
  v6 defaults to a proprietary caching component whose use means accepting
  Gradle's commercial terms; `basic` is the open-source path over the GitHub
  Actions cache.
- Status claims across `README.md`, `docs/VALIDATION.md`, `CONTRIBUTING.md` and
  `CLAUDE.md` corrected once they became untrue, including the repeated
  prediction that MediaPipe would be the thing that broke.
- `app/src/main/assets/README.md` now gives a direct, scriptable YAMNet URL
  with size and checksum instead of sending people to Kaggle.

### Not changed, deliberately

- **`targetSdk` stays at 36** while compileSdk moves to 37. compileSdk changes
  what the app is compiled against; targetSdk changes how Android behaves
  towards it at runtime, and nothing here has run on a phone.
- **Kotlin stays at 2.4.20** rather than falling back to the 2.2.10 AGP 9
  supplies.
- **The debug APK is ~178 MiB** and ships four ABIs. That is ML Kit and
  MediaPipe bundling models and native code, in a build with no minification.
  Documented rather than restructured, since cutting ABIs trades away emulator
  support for a build nobody has run yet.

### Still untested

Everything needing hardware. No percept has ever come off a real phone. Motion
and posture thresholds are reasoned from magnitudes, the magnetic baseline time
constant is a guess, and the `ImageProxy` lifetime in `VisionSensor.onFrame`
remains the most suspect code here.

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

Initial draft. Predates this repository, which was opened at 0.2.0, so there
is no tag to compare against and the heading is deliberately not a link.

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

[Unreleased]: https://github.com/Tubifix77/synth-senses/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/Tubifix77/synth-senses/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/Tubifix77/synth-senses/releases/tag/v0.2.0
