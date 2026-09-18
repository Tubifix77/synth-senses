# CLAUDE.md

Guidance for Claude Code working in this repo.

## What this is

An Android app that turns a phone into the sensory apparatus for an AI running
elsewhere. It samples six sense blocks on-device, gates them through a
habituation model, and streams percepts over a two-way WebSocket that the remote
end can steer.

Read `README.md` for the design and `docs/PROTOCOL.md` for the wire format.
Read `docs/VALIDATION.md` before touching any of the maths — it records which
algorithms were verified, and which earlier designs failed and why.

## Status: green

All four CI jobs pass — assemble debug, android lint, unit tests, receiver smoke
test. A debug APK is produced. 25 of 25 unit tests pass.

How it got there, because the shape of it is the lesson: Kotlin source errors,
then build DSL errors, then three rounds of dependency metadata, then test
source errors, then four real test failures. Every layer was hiding the next.

The dependency ceiling still matters and is now enforceable locally. Lifecycle
is pinned to **2.10.0**: the 2.11.0 family drags in
`lifecycle-runtime-compose-android`, which demands `minCompileSdk=37` and AGP
9.1, and it arrives transitively so pinning the direct artifacts cannot fix it.
`tools/transitive_sweep.py` resolves the whole graph and checks every AAR.

Moving to AGP 9.x + compileSdk 37 is still the cleaner long-term answer, and is
now a real choice rather than a forced one. AGP 9 is a major release with DSL
breaks, so expect several iterations if you take it on.

What is genuinely untested is everything that needs hardware. No percept has
ever come off a real phone.

## The critical constraint: there is no local Android toolchain

The machine has **no JDK, no Android SDK, no adb, no Gradle**. `python`, `git`
and `gh` are present and authenticated.

So **CI is the compiler.** The loop is:

```bash
git add -A && git commit -m "..." && git push
gh run list --repo Tubifix77/synth-senses --workflow build.yml --branch main --limit 1
gh run view <id> --repo Tubifix77/synth-senses --json jobs -q "([.jobs[]|.name+\" :: \"+(.conclusion//.status)]|join(\"\n\"))"
gh run view <id> --repo Tubifix77/synth-senses --log-failed
```

Each cycle costs roughly four minutes. **Batch fixes; do not push one-line
guesses.** Where a fact can be established by inspecting a published artifact,
establish it that way instead of spending a cycle on it — see below.

If the user is willing to install Temurin 17 plus Android command-line tools
(~2 GB), local `gradle assembleDebug` and `adb install` become possible and the
loop gets far faster. Worth offering; do not install unasked.

## Resolve facts, do not recall them

This project has been bitten repeatedly by plausible-sounding version and API
claims. Every dependency version here was resolved against live repositories,
and every uncertain API confirmed by reading the published artifact.

Useful techniques, all pure Python with no dependencies:

- **Latest versions:** fetch `maven-metadata.xml` from
  `https://dl.google.com/dl/android/maven2/` or
  `https://repo1.maven.org/maven2/`.
- **Does this API exist, and what is its signature?** Download the `.aar`, open
  `classes.jar`, and grep the `.class` constant pool for the method name. This
  is how `ProcessCameraProvider.Companion.awaitInstance(Context)` was confirmed
  rather than assumed.
- **Will AGP accept this dependency?** Read
  `META-INF/com/android/build/gradle/aar-metadata.properties` inside the AAR.
  Keys are `minCompileSdk` and `minAndroidGradlePluginVersion`.

A hard-won lesson: **that last check must cover transitive dependencies, not
just direct ones.** Checking only the direct list is exactly what let
`lifecycle-runtime-compose` through. `tools/transitive_sweep.py` now does this
properly — it resolves the full debug runtime graph the way Gradle does (module
metadata, BOM platforms, constraints, highest-version-wins to a fixed point) and
reads each AAR's metadata over HTTP range requests. Run it before any dependency
change; exit code 1 means CI would fail:

```bash
python tools/transitive_sweep.py                       # check the current pins
python tools/transitive_sweep.py --set androidx.lifecycle:lifecycle-service=2.11.0
```

## Do not blindly merge the Dependabot PRs

Several open PRs would break the build:

- **okhttp 4.12.0 → 5.5.0** — major version, API changes in `Link.kt`.
- **mediapipe tasks-audio 0.10.35 → 1.0.0** — the 0.10 line is *proven to
  compile*; 1.0.0 is unverified. `AudioSensor.kt` is the only consumer.
- Action bumps are low risk but change nothing useful.

The dependency versions here are pinned to a **deliberately computed ceiling**
for AGP 8.x. Any bump needs the aar-metadata check above, or it will simply
reintroduce the failure being fixed.

## Code map

```
app/src/main/java/net/synthsenses/
  MainActivity.kt          Compose control panel
  senses/
    SenseService.kt        the loop; foreground service; command dispatch
    Percept.kt             wire schema + tokens() — the stimulus vocabulary
    Narrator.kt            deterministic on-device prose
    Habituation.kt         attention model          <- validated, tested
    PlaceMemory.kt         radio place learning     <- validated, tested
    TempoSensor.kt         offline solar position   <- validated, tested
    VisionSensor.kt        CameraX + ML Kit
    AudioSensor.kt         AudioRecord + YAMNet
    SpeechSensor.kt        offline transcription
    BodySensor.kt          proprioception
    SelfSensor.kt          interoception
    RadioSensor.kt         BLE + WiFi + cell
    Link.kt                WebSocket/HTTP transport + command vocabulary
    Spool.kt               bounded disk queue
    Voice.kt               TTS out
app/src/test/java/.../     JVM unit tests, no emulator needed
tools/receiver.py          stdlib two-way receiver + command console
tools/transitive_sweep.py  whole-graph AAR metadata check, run before dep bumps
```

## Invariants

These are load-bearing. Breaking them is a behaviour change, not a refactor.

- **Sensors degrade, never fabricate.** Missing hardware or a denied permission
  yields `null`. A receiver distinguishing "no barometer" from "pressure is
  1013" matters.
- **Raw identifiers never leave the device.** MACs and BSSIDs are hashed
  (FNV-1a, 10 hex chars) before they enter a percept.
- **Frames and audio buffers never leave the device and are never written to
  disk.** Only derived labels, transcripts and numbers.
- **New senses ship off by default**, especially anything needing a
  special-grant permission.
- **`Habituation` and `PlaceMemory` take a `File`**, with a `Context`
  convenience constructor. That is deliberate — it is the only reason the maths
  is unit-testable without Robolectric. Do not collapse it back.
- **Continuous quantities are bucketed in `Percept.tokens()`.** A room at 310
  lux and the same room at 340 lux must produce the same token, or nothing ever
  habituates.

## Changing the wire format

Adding a nullable field is fine and needs no version bump.

Removing or renaming a field, or changing a type, breaks receivers: bump
`SCHEMA_VERSION` in `Percept.kt`, update `docs/PROTOCOL.md`, and note it in
`CHANGELOG.md`.

## The tests encode past failures

`PlaceMemoryTest` is not generic coverage. The BLE-with-randomised-MACs case and
the adjacent-room case are the exact scenarios that broke two earlier designs of
the coverage metric. `TempoSensorTest`'s night assertions are the regression
guard for a bug where midnight and noon returned identical solar elevation.

If a change to those algorithms makes these tests fail, the change is wrong
until proven otherwise.

## Docs

The "never compiled" claims in `README.md`, `docs/VALIDATION.md` and
`CONTRIBUTING.md` have been corrected, along with the repeated prediction that
MediaPipe `tasks-audio` would be the breakage — it compiled fine and produced
zero errors, while the real failure was CameraX code nobody had flagged.
`app/src/main/assets/README.md` now gives the direct, scriptable YAMNet URL
(`https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/latest/yamnet.tflite`,
4,126,810 bytes, sha256
`4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf`, all three
verified against the copy on disk) rather than sending people to Kaggle.

The rule that produced those corrections still applies to whatever you write
next: **state only what has actually been observed, and update a status claim
only once it is genuinely untrue.** The honesty of the status section is a
feature of this repo, not boilerplate to be tidied away. `docs/VALIDATION.md`
records a 96.5% room-separation rate rather than rounding it to "works" for
exactly this reason.

## Still unmeasured

Listed fully in `docs/VALIDATION.md`. The headline items: motion and posture
thresholds in `BodySensor` are reasoned from magnitudes rather than measured,
the magnetic baseline time constant is a guess, and the `ImageProxy` lifetime
hack in `VisionSensor.onFrame` holds the proxy open for a fixed 1.5 s — the most
suspect code in the repo.

Fixing those needs a physical device and `adb logcat`.
