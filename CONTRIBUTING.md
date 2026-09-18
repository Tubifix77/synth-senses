# Contributing

## The state of things

It builds, and the unit tests run: CI assembles a debug APK, runs Android Lint
and executes the JVM unit-test suite on every push. It covers the three
validated algorithms plus the wire schema, the stimulus vocabulary, the offline
backlog, inbound command parsing, the narrator and the identifier hashing. What has never happened is any of
it running on a phone. No percept has ever been produced by real hardware.

So the most valuable contribution now is a device and half an hour of
`adb logcat` — see the table below, and the list of unmeasured constants in
[docs/VALIDATION.md](docs/VALIDATION.md).

## Setup

```bash
git clone https://github.com/Tubifix77/synth-senses.git
cd synth-senses
```

Open in Android Studio. The build is on AGP 9.4.1, which needs Gradle 9.6 or
newer and JDK 17, so the IDE has to be recent enough to support that — check
Google's [AGP compatibility table](https://developer.android.com/build/releases/gradle-plugin)
rather than trusting a version name here, since it moves. The Gradle wrapper
JAR isn't committed; Android Studio generates it on sync, or run
`gradle wrapper` once.

Then drop `yamnet.tflite` into `app/src/main/assets/` — see
[the assets README](app/src/main/assets/README.md). Not required to build.

Test the wire protocol without a phone:

```bash
python3 tools/receiver.py        # stdlib only
```

## Especially wanted

**Measured thresholds.** Several constants are reasoned rather than measured, and
each is a one-line change once someone has real data:

| Where | Constant | How to measure |
|---|---|---|
| `BodySensor.classifyMotion` | motion boundaries | log `accel_rms`/`gyro_rms` while walking, driving, sitting |
| `BodySensor.posture` | gravity-vector cutoffs | log `posture` against known phone positions |
| `BodySensor` | `MAG_BASELINE_ALPHA` | watch `magnetic_anomaly` near motors and metal |
| `Habituation` | `RATE`, `TAU_MS` | does it go quiet too fast, or not fast enough? |
| `PlaceMemory` | `RECOGNISE`, `MERGE` | do your rooms fragment or collapse? |

If you retune anything, please include the observations in the PR. A number
without a measurement behind it is what's already there.

**The `ImageProxy` lifetime in `VisionSensor.onFrame`** is the single most
suspect piece of code in the repo. It holds the proxy open for a fixed 1.5 s
while ML Kit reads the planes. A proper fix closes it on task completion instead.

**New senses.** Ideas that fit the design: notification listener as a social
sense, NFC tags as a deliberate "this object is X" input, ultrasonic chirp-and-echo
presence detection, foldable hinge angle, usage-stats foreground app.

Anything with a special-grant permission (notification access, usage stats) must
be opt-in and off by default.

## Conventions

- Kotlin official style, 4 spaces, 100-column soft limit. `.editorconfig` has it.
- **Comment the why, not the what.** The existing code explains permission
  gotchas, why a threshold is what it is, and what was tried and rejected. Match
  that.
- Every sensor degrades gracefully. Missing hardware or a denied permission
  yields `null`, never a crash and never a fabricated value.
- Nothing invented. If a sensor isn't there, the field is `null` — a receiver
  distinguishing "no barometer" from "pressure is 1013" matters.
- Raw identifiers never leave the device. Hash MACs and BSSIDs.

## Changing the wire format

Adding a nullable field is fine and needs no version bump.

Removing or renaming a field, or changing a type, breaks receivers. Bump
`SCHEMA_VERSION` in `Percept.kt`, update [docs/PROTOCOL.md](docs/PROTOCOL.md),
and note it in [CHANGELOG.md](CHANGELOG.md) under **Changed**.

## Pull requests

1. One concern per PR.
2. Say whether you compiled it and whether you ran it on a device. "Untested" is
   an acceptable answer — just say so.
3. Update the docs in the same PR.
4. If you changed any of the maths, say how you checked it.

## Privacy is a feature here

This app watches rooms and listens to people. Changes that widen what leaves the
device, weaken hashing, or make a sense harder to switch off need a clear
justification in the PR description. Defaults stay conservative: new senses ship
off.
