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

All six CI jobs pass — assemble debug, android lint, unit tests, receiver
smoke test, and the emulator at API 28 and API 36. A debug APK is produced.
Every unit test passes; the suite was 100 tests at the time of writing, plus
six instrumented tests on each emulator, which are numbers worth re-reading
from CI rather than trusting here.

The emulator legs are the two ends of the range the app is developed for: a
Huawei P20 Lite on Android 9 and a Xiaomi 14T Pro on Android 14. They cannot
measure anything on the hardware list, but they answer "does it start and
survive a rotation", which nothing did before they existed. The API 28 leg
found a real bug on its first run.

The APK has been opened and checked, which is how the stray `assets/README.md`
that was being shipped to devices got found. It is about 178 MiB, almost all of
it ML Kit and MediaPipe native libraries across four ABIs.

How it got there, because the shape of it is the lesson: Kotlin source errors,
then build DSL errors, then three rounds of dependency metadata, then test
source errors, then four real test failures. Every layer was hiding the next.

The toolchain is **AGP 9.4.1, Gradle 9.7.1, compileSdk 37, Kotlin 2.4.20**,
and nothing is pinned below its current release any more. The AGP 8.x ceiling
was held only while the project had never compiled; once it had, four separate
artifacts were stuck behind it and the gap was widening on every Dependabot
run, so the wall came down. It took two CI iterations, because the DSL was
checked against the AGP jars first rather than discovered a line at a time.

Two things about AGP 9 that are not obvious and cost a cycle anyway:

- It compiles Kotlin itself and **refuses to apply `org.jetbrains.kotlin.android`**.
  Remove that plugin. Keep `org.jetbrains.kotlin.plugin.compose`.
- It brings its own Kotlin Gradle plugin, 2.2.10 at AGP 9.4.1. Left alone that
  silently downgrades the language version, so the root `buildscript` raises it
  to 2.4.20. The Compose compiler plugin must stay on that same version.

`targetSdk` stays at **36** on purpose. compileSdk changes what the app is
compiled against, targetSdk changes how Android treats it at runtime, and no
one can verify the latter until this runs on a phone.

`tools/transitive_sweep.py` reads compileSdk, minSdk and the AGP version out of
the build files, so a bare run always checks against the real ceiling. Pass
`--sdk` / `--agp` only to ask a what-if question.

What is genuinely untested is everything that needs hardware. No percept has
ever come off a real phone.

The Potluck side is one step further on: as of 2026-10-21 the bridge is
paired against the real hub, the certificate is pinned, and a hand-made
percept wrote `battery = 0.95` to the live cell and came back `applied`. So
the chain below the phone works. Nothing about the phone end of it does yet,
and the rate budget, the refresh-before-stale behaviour and the drain loop
under real cadence are all still unexercised — a one-shot write cannot touch
them.

## How work lands: main, and nothing else

Trunk only. Commit and push straight to `main`. No feature branches, no pull
requests for your own work, no merge commits.

A red `main` is acceptable here and always has been — CI *is* the compiler, so
finding out something does not build is the normal result of a push, not an
incident. The next commit fixes it. There is no branch protection, no reviewer
waiting and no one else pulling, so a branch buys nothing and costs a PR, a
merge and a cleanup.

It costs something worse, too: this workflow only runs on push to `main` and on
`pull_request`, so a branch with no PR gets no CI at all. Branching to "test it
safely" therefore forces you to open a PR just to get a build, which is pure
ceremony.

Dependabot's own branches are the exception, because Dependabot makes them.
Merge or close those; do not create more.

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

## Dependabot: check, then merge

Every open PR has now been checked against the artifacts rather than against
intuition, and both of the reasons previously recorded here were wrong.

- **mediapipe tasks-audio 0.10.35 → 1.0.0 — merged.** This was flagged in three
  places as the likely breakage. It was the cleanest bump of the batch: the
  sweep passes, tasks-audio ships the same 12 classes, and everything
  `AudioSensor.kt` names is present in tasks-core 1.0.0. The one class removed
  anywhere in that library belongs to the vision segmenter.
- **okhttp 4.12.0 → 5.5.0 — landed.** It was never an API problem: every
  okhttp symbol `Link.kt` names is present in 5.x, checked against the
  bytecode. It was the *same* metadata failure as lifecycle — okhttp 5 is a
  multiplatform module whose `okhttp` coordinate redirects to `okhttp-android`,
  which declared `minCompileSdk=37` at 5.5.0. Moving to compileSdk 37 let it in
  as written. For the record, in case a downgrade is ever wanted: 4.12.0 has no
  published advisories, and 5.4.0 is the highest release that builds at
  compileSdk 36.
- **gradle/actions v4 → v6 — taken, but not as offered.** v6 extracts caching
  into `gradle-actions-caching`, a proprietary component outside the MIT
  licence, and its release notes state that upgrading means accepting Gradle's
  commercial Terms of Use. The new `cache-provider` input defaults to
  `'enhanced'`, which loads it. The workflow therefore pins
  `cache-provider: 'basic'`, the open-source implementation over the GitHub
  Actions cache: maintained action and Node 24 runtime, no commercial terms.
  `cache-disabled: true` removes all doubt if you would rather. **Do not drop
  that input** — the default is not the neutral option.
- **checkout v7, setup-java v6, setup-python v7, upload-artifact v7 — merged.**
  All four are the same change underneath, a Node 24 runtime, which
  GitHub-hosted runners already satisfy. Every input this workflow passes still
  exists at the target version; each manifest was read rather than assumed.

The dependency versions here are pinned to a **deliberately computed ceiling**
for the current toolchain. Any bump needs the aar-metadata check above — and note what the
okhttp case shows: a major version can be blocked by metadata while its API is
perfectly compatible, and the reverse is equally possible. Check both, and
check the artifact rather than the changelog.

## One thing already decided, so it does not get re-litigated

`tools/.mvncache/` — 4 MB of public Maven POMs and module metadata — was
committed by accident in 855d20d and untracked again one commit later, because
`.gitignore` has no inline comments and the pattern carried one. The files are
still in history and are staying there. Nothing in them is sensitive, they are
public metadata, and rewriting published history on a repo anyone may have
cloned costs more than 4 MB of pack file is worth.

## Code map

```
app/src/main/java/net/synthsenses/
  MainActivity.kt          Compose control panel
  senses/
    SenseService.kt        the loop; foreground service; command dispatch
    Percept.kt             wire schema + tokens() — the stimulus vocabulary
    Vocabulary.kt          closed label lists, in ordinal order  <- order is wire
    Permissions.kt         which runtime permissions, per API    <- tested
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
tools/digest.py            summarise a percept log; redacts OCR text and
                           transcripts unless --raw
tools/transitive_sweep.py  whole-graph AAR metadata check, run before dep bumps
tools/test_receiver_ws.py  RFC 6455 against the receiver, plus its stdout contract
tools/test_transitive_sweep.py  the sweep's version ordering and ceiling detection
tools/test_digest.py       the digest, and above all that redaction holds
tools/test_docs.py         docs/INTEGRATION.md's facts against the source
tools/apk_facts.py         reads a built APK's binary manifest; CI gates on it
tools/potluck_bridge.py    percepts -> eleven numbers in a Potluck cell
tools/test_potluck_bridge.py  its ordinals, budget, and that absence stays absent
```

All five tool suites are stdlib-only and run in CI on every push, as does
`apk_facts.py` against the APK that job just built. `python3
tools/<name>.py` runs any of them locally, which is faster than a CI cycle and
needs no toolchain.

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
- **The phone's own telephony outranks every sense.** One target device is a
  household emergency line. `AudioSensor` stands down whenever
  `AudioManager.mode` is anything but `MODE_NORMAL` — from the first ring, not
  from the answer — and nothing here may hold the mic, change a volume, or pin
  the CPU in a way that could cost a call. Losing a sound label is always the
  right trade.
- **The app makes no sound and shows no banner, ever.** It is an appliance on
  a phone that is meant to sit untouched for months. The foreground
  notification is required by Android and is the only thing it may put on
  screen; it is `IMPORTANCE_LOW`, `setSilent(true)`, and **re-posted only when
  its text actually changes**, which is why that text is built from enabled
  senses and link state rather than from anything per-sample.

  The narration used to go there, refreshed on every sample — above the
  habituation return, so even the samples deemed not worth sending re-posted
  it. On EMUI a notification whose text changed is re-bannered with a chime.

  The reason this is an invariant and not a preference: the test phone is
  always muted, so it was invisible there, while the P20 is the household
  emergency line and **has its ringer on precisely because it must**. Telling
  the user to mute the phone is telling them to break the phone's actual job,
  so "the user can silence it" is not an available answer here.
- **`Habituation` and `PlaceMemory` take a `File`**, with a `Context`
  convenience constructor. That is deliberate — it is the only reason the maths
  is unit-testable without Robolectric. Do not collapse it back.
- **Continuous quantities are bucketed in `Percept.tokens()`.** A room at 310
  lux and the same room at 340 lux must produce the same token, or nothing ever
  habituates.
- **A change to the published Potluck values needs telling, not just testing.** The
  Potluck Desk project pins `tools/potluck_bridge.py --vocabulary` and asserts
  its labels against it, so a rename here breaks a build over there. That is
  the point — better a red build than a silently relabelled dial — but say so
  before it lands. Renaming, reordering or inserting a word is breaking;
  appending a whole new value is not. **Removing one is worse than either**:
  their view matches only an inbox carrying the whole set, so the phone's
  desktop disappears entirely rather than losing a dial.
- **The lists in `Vocabulary.kt` are append-only.** A consumer that cannot
  carry strings carries the index instead, which makes their order part of the
  wire format even though no percept ever shows it. Inserting a value silently
  relabels everything after it on the far side. `VocabularyTest` pins the
  order; `tools/test_docs.py` ties the docs to it.

## A trap the JVM tests cannot see

`testImplementation("org.json:json:20260814")` shadows `android.jar`'s stub
with the real org.json, which is what makes `Percept`, `Spool` and the
command parsing unit-testable at all. It also means **the JVM tests run
against a different JSON implementation from the phone**, and the two
disagree in at least one way that matters:

**`JSONObject.optString(key, fallback)` returns the four-character string
`"null"` on Android when the value is `JSONObject.NULL`.** The real org.json
returns the fallback. So this looked correct and was not:

```kotlin
name = o.optString("name", "").takeIf { it.isNotBlank() }   // "null" on a phone
```

Every unnamed place came back named `"null"` after one reload, and the
narration said "I am in null." Unnamed is the normal case, since nothing names
a place unless someone sends `name_place`. The unit tests passed throughout;
it took an evening on hardware to see it. `Link.kt` had already guarded the
same call with `isNull`, which is what makes it an oversight rather than a
surprise.

Guard it as `if (o.isNull(k)) null else o.optString(k, "")`, and put the
regression test in **`androidTest`** — that is the only source set that sees
Android's own implementation. `PlaceNameRoundTripTest` is the example.

Anything else that round-trips through `JSONObject` on the phone deserves the
same suspicion, and the same test location.

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

## What comes next is written down

[`docs/ROADMAP.md`](docs/ROADMAP.md) holds the sequence, and the order is not
negotiable: **a phone first, then the bridge.**

The bridge is the piece connecting this project to the author's separate work
on machine consciousness. This repo is the sensory apparatus; that one is meant
to have the experience. It is deferred on purpose — designing an interface to a
stream nobody has ever seen real output from is guesswork, and one evening of
watching actual percepts will say more than any amount of speculation.

Two things to carry into that work:

- **The outbound half is done.** Percepts leave the receiver as JSON lines on
  stdout, envelope stripped, so the bridge is a program that reads stdin.
- **The return path is built.** Commands go in as JSON lines on the
  receiver's stdin, symmetric with percepts out on stdout, and the phone's
  replies come back on stdout too. A reply always carries `type`; a percept
  never does, and that discriminator is load-bearing for every consumer.
  Unknown or malformed commands are refused by the receiver and never reach
  the phone. `tools/test_docs.py` checks the receiver's whitelist against
  `Link.kt`'s own constants, so the two cannot drift.
- **Anything reading the receiver's stdout must pass `encoding="utf-8"`.**
  On Windows a pipe defaults to cp1252 and the stream dies on the first
  non-Latin-1 character in a narration or transcript — on a word someone
  happened to say. The receiver and the printing tools force UTF-8 on their
  own streams; consumers must do it on theirs. CI is Linux, so this never
  shows there.

The bridge itself lives **here**, not over there, with a small input adapter on
the far side. Its work is sensor-domain knowledge, and turning an intent back
into a command is pure `docs/PROTOCOL.md`. But the seam must be a neutral
format, JSON lines, never a call into the other project's API: the percept
schema is versioned and tested while an early consciousness project's input
format will churn, and importing its client would point the dependency from
the stable side at the experimental one.

[`docs/INTEGRATION.md`](docs/INTEGRATION.md) is the spec handed to whoever
builds the far side. It documents only what runs, marks the return path as not
reachable from code, and `tools/test_docs.py` checks its numbers against the
source on every push, because a spec someone codes against without reading the
Kotlin is worse than nothing when it goes stale.

Do not design the bridge against assumptions about the other project. The
roadmap lists the questions that need answering first, and separates the half
that is blocked on them from the half that is blocked on nothing.

## The hardware test tests the app, not the phone

Worth holding onto, because the unmeasured list reads the other way. "Measure
the thresholds" invites calibrating `BodySensor` to whatever IMU is to hand,
which is overfitting one device and calling it validation — a Xiaomi's
`lsm6dsvq` and a six-year-old Huawei's sensor do not agree on what `0.55`
means.

The first evening on hardware bore it out. What it found about the phone —
no barometer, a gyroscope, Android 16, 15.4 points of battery an hour — was
worth nothing beyond that handset. What it found about the app was worth the
evening: places renaming themselves `"null"`, every CI APK deaf, every update
wiping the phone, three kinds of empty sound-event list, `heading_deg` not
being the camera direction, and place learning being unusable on BLE alone.

Read `docs/ROADMAP.md`'s list as questions about behaviour, not requests for
constants.

## Still unmeasured

Listed fully in `docs/VALIDATION.md`. The headline items: motion and posture
thresholds in `BodySensor` are reasoned from magnitudes rather than measured,
the magnetic baseline time constant is a guess, and the `ImageProxy` lifetime
hack in `VisionSensor.onFrame` holds the proxy open for a fixed 1.5 s — the most
suspect code in the repo.

Fixing those needs a physical device. It needs `adb logcat` less than it did:
`tools/digest.py` now reads both halves of the motion evidence — `accel_rms`
**and** `gyro_rms` per motion state, since `classifyMotion` tests gyro in three
of its branches — and flags a sense that worked and then stopped, which is the
signature of the `ImageProxy` stall and of mic contention. Both of those fail
by going quiet rather than by throwing, so they are visible in a percept log
without a cable. The magnetic time constant still needs logcat.
