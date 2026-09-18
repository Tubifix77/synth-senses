# Validation

What has actually been verified, what the verification caught, and what is still
untested. Written so you know which parts to trust.

## Summary

| Component | Status |
|---|---|
| `tools/receiver.py` | **tested in CI** — RFC 6455 handshake, framing, backlog, HTTP fallback |
| `tools/transitive_sweep.py` | **tested in CI** — version ordering, ranges, ceiling detection |
| `Habituation.kt` curve | prototyped, validated, now unit-tested |
| `PlaceMemory.kt` coverage metric | prototyped, **failed**, redesigned, revalidated — then the unit tests found a **fourth** failure |
| `TempoSensor.kt` solar maths | prototyped, **bug found**, fixed, validated against almanac, now unit-tested |
| All Kotlin | **compiles** — CI assembles a debug APK, lints, and runs 71 unit tests per push |
| The APK itself | **opened and checked** — manifest, dex contents and packaged assets |
| On-device behaviour | **entirely untested** — thresholds are reasoned, not measured |

Every algorithm here has had a real bug caught by testing it, and one of them
had a bug that only appeared once the tests ran against the shipped Kotlin
rather than against a model of it. That is the argument for the tests, and the
reason to be suspicious of everything in the last row.

## Receiver, end to end

Originally a manual session: a stand-in client performed a real WebSocket
handshake, streamed percepts, flushed a backlog, and responded to commands the
way `SenseService.handle()` would. That session proved the design but nobody
could re-run it, which is a poor basis for claiming a transport works.

`tools/test_receiver_ws.py` now does the core of it on every push. It speaks
RFC 6455 as a real client: masked frames, a hello, a percept, a backlog of
two, a command reply, a message split across two fragments, a ping, and a
close — then checks what reached the log, that the fragmented payload was
reassembled byte for byte, and that a reply was not miscounted as a percept.

The handshake is asserted against **RFC 6455's own worked example** rather
than against a magic constant copied into the test, because writing that test
produced exactly one failure and it was a transposed character in the test's
copy of the constant. The receiver was right. The spec is a better oracle than
a transcription of it.

Verified in the original manual session, and where marked, now on every push:

- RFC 6455 handshake, `Sec-WebSocket-Accept` computed correctly
- masked client frames parsed, including fragmentation and ping/pong
- `percept` frames rendered with salience bar, sense summary and novel tokens
- `backlog` frames expanded and each percept logged
- all eleven commands round-tripped: `sample`, `look front`, `read`, `listen`,
  `speak`, `attend s 2.0`, `set interval_ms 15000`, `places`, `name_place`,
  `forget`, `status`
- `ack`, `places` and `status` replies rendered
- HTTP POST fallback on the same port, single and array bodies
- a percept with every sense block `null` handled without crashing
- JSONL logging

## Habituation curve

Model: each stimulus token has a trace `h`. On exposure,
`h ← h' + (1 − h') · RATE` where `h'` is `h` decayed by
`exp(−Δt / TAU)`. Token salience is `1 − h'`. Percept salience is
`0.6 · max + 0.4 · mean` over the token set.

With `RATE = 0.18` and `TAU = 45 min`, sampling a static scene every 5 s:

| Exposure | Salience |
|---|---|
| 1 | 1.000 |
| 2 | 0.820 |
| 3 | 0.673 |
| 5 | 0.454 |
| 10 | 0.173 |
| 20 | 0.032 |
| 40 | 0.011 |
| 60 | 0.010 |

So a static scene stops being reported after roughly two minutes at a 5 s
interval. Recovery after leaving:

| Time away | Salience on return |
|---|---|
| 0 min | 0.011 |
| 5 min | 0.115 |
| 20 min | 0.366 |
| 45 min | 0.636 |
| 120 min | 0.931 |
| 360 min | 1.000 |

Novelty breaking through a habituated scene — a person entering a room that had
decayed to 0.011:

```
  salience 0.736      (o:Person and s:Speech are novel)
  after 13 more exposures: 0.064
```

The `max` term is what makes this work. A pure mean would have buried two novel
tokens among a dozen habituated ones.

Dishabituation, multiplying every trace by 0.5:

```
  before 0.011  ->  after 0.505
```

Which is the intended behaviour: a bang makes it look around at everything, not
just report the bang.

### Not validated

`RATE` and `TAU` are plausible, not tuned against real data. If output feels too
chatty, raise `RATE`; if it goes quiet on things you care about, shorten `TAU`.
The token vocabulary matters more than either constant.

## Place recognition

### First attempt: failed

Weighted symmetric Tanimoto over the anchor sets — shared weight over union
weight. Simulated with a 14-anchor room, 25% of anchors dropping out per scan,
±8 dBm drift, and 3 transient devices per visit:

```
  revisit 1: 0.69  -> merge
  revisit 2: 0.51  -> recognise
  revisit 3: 0.57  -> recognise
  revisit 4: 0.41  -> NEW (wrong)
  revisit 5: 0.69  -> merge
  ...
  min 0.41  mean 0.60
```

One revisit in eight minted a spurious new place. Worse, in a dense environment
with 60% dropout every single revisit failed, and BLE-only mode — 3 fixed devices
plus randomised MACs — recognised 1 visit in 6.

The failure mode is structural. A symmetric metric punishes a fingerprint for
containing things the other doesn't, but a scan legitimately contains transient
devices, and legitimately misses anchors that were there last time.

### Second attempt: closer

Asymmetric coverage of the remembered place by the current scan, weighting each
anchor by learned persistence. BLE-only jumped to 10/10. But dense environments
still failed, because if you only ever see a third of a place's anchors, coverage
caps at a third.

### Third attempt: works

Normalise by what the place *expects* to show:

```
  expected = Σ (signal weight × persistence)  over the place's anchors
  achieved = Σ  signal weight                 over anchors visible now
  coverage = min(1, achieved / expected)
```

Plus a persistence filter: an anchor must have been seen at least twice, and on
at least 35% of visits, to enter the denominator at all. Without that filter,
once-seen randomised MACs inflate `expected` and drag every match down — which is
exactly what the BLE-only case showed.

Signal weight maps −100 dBm → ~0 and −30 dBm → 1. Recognition threshold 0.50,
merge threshold 0.75. A borderline match is reported but doesn't teach the
centroid, so a doorway can't slowly blend two rooms into one.

Results, 12 revisits each, after 6 training visits:

**Should recognise:**

| Scenario | Min | Mean | Recognised |
|---|---|---|---|
| Quiet room, light drift | 0.81 | 0.97 | 12/12 |
| Dense env, 60% of anchors drop | 0.33 | 0.66 | 8/12 |
| Very dense, 75% drop, 20 transients | 0.00 | 0.23 | 3/12 |
| Brutal, 85% drop, 30 transients | 0.00 | 0.00 | 0/12 |
| BLE only, 3 fixed + 4 random/visit | 1.00 | 1.00 | 12/12 |
| BLE only, 2 fixed + 6 random/visit | 1.00 | 1.00 | 12/12 |

**Should stay new:**

| Scenario | Coverage | Shared anchors | Verdict |
|---|---|---|---|
| Adjacent room, 4 shared access points | 0.41 | 4 | correctly new |
| Street outside | 0.00 | 0 | correctly new |
| Empty scan | 0.00 | 0 | correctly new |
| One random device | 0.00 | 0 | correctly new |
| Unrelated 40-device scan | 0.00 | 0 | correctly new |

**How much must be visible to recognise a known place:**

| Anchors visible | Coverage | Verdict |
|---|---|---|
| 15% | 0.39 | new |
| 25% | 0.93 | recognised |
| 40%+ | 1.00 | recognised |

### Fourth failure: the bootstrap, found by the unit tests

Everything above validated the *metric*. The prototype trained each place by
calling `observe()` directly, so it never exercised the path a real phone takes,
which is `recognise()` deciding for itself that this scan belongs to a place it
already has. The first time the unit tests ran against the shipped Kotlin, four
of the nine `PlaceMemoryTest` cases failed, and the reason was total:

```
  18 visits to one quiet room  ->  18 places
```

A place created moments ago has been visited once, so every anchor it holds has
`seen = 1`. The persistence filter demands `seen >= MIN_SEEN`, which is 2. So
every one of its own anchors was skipped, its expected weight was zero, its
coverage against any scan was zero, it was never recognised a second time, and
therefore never received a second observation. The filter that makes the metric
robust at maturity made it impossible to reach maturity. Not a degradation — a
deadlock, on every input, for the entire life of the app.

The fix is a learning window. For its first `LEARNING_VISITS` (6) visits a place
is scored on everything it has seen, weighted by how often; it is recognised at
`LEARNING_RECOGNISE` (0.35) rather than 0.50, because its denominator still
holds the passers-by of its first visit at full persistence and that biases its
coverage down to ~0.66 where a settled place scores ~0.94; and every recognition
teaches it. Nothing about a settled place changed.

Verified before the Kotlin was written, by porting `kotlin.random.XorWowRandom`
bit-for-bit and replaying the test scenarios on the exact fingerprints JUnit
generates from `Random(7)`. Across 2000 random seeds, the eight of the nine
cases that the simulation reproduces went from 50% passing to 98.8%.

### Remeasured, against the shipped class

The tables above are the prototype's. These are the same scenarios driven
through `PlaceMemory.recognise()` itself, 12 revisits after 6 training visits,
averaged over 200 seeds:

| Scenario | Recognised | Mean coverage | Prototype said |
|---|---|---|---|
| Quiet room, light drift | 12/12 | 0.94 | 12/12, 0.97 |
| Dense env, 60% of anchors drop | 8/12 | 0.52 | 8/12, 0.66 |
| Very dense, 75% drop, 20 transients | 0/12 | 0.14 | 3/12, 0.23 |
| Brutal, 85% drop, 30 transients | 0/12 | 0.04 | 0/12, 0.00 |
| BLE only, 3 fixed + 4 random/visit | 12/12 | — | 12/12 |
| BLE only, 2 fixed + 6 random/visit | 12/12 | — | 12/12 |

Separation, over 2000 seeds:

| Should stay new | Claimed as a known place |
|---|---|
| Street outside, unrelated 40-device scan | 0 out of 4000 |
| One random device | 0 out of 2000 |
| Adjacent room, 4 shared access points | 69 out of 2000 |

That last row is the honest one, and it is worth stating plainly rather than
tuning away: keeping a room distinct from the room next door is a **96.5%**
property of this metric, not a guarantee. 65 of those 69 were settled places
claiming the neighbour, not learning ones, so it is a property of the original
coverage metric rather than of the learning window. `PlaceMemoryTest` uses a
fixed seed on which it separates correctly.

`LEARNING_VISITS` has a cliff at 9: a place that is still learning is matched
leniently, so leaving it lenient for longer than it takes to settle is exactly
how the neighbour gets absorbed. Do not raise it past 8 without re-running this.

### Known limitation

A location with no stable anchors at all — a station concourse where 85% of what
you see differs every scan — keeps minting new place IDs. Arguably correct, since
that isn't a stable place, but if it bothers you, extend the training period
before anchors are trusted, or lean on `MIN_SEEN`.

### Simulation caveat

This is a synthetic model of RSSI drift and dropout, not field data. Real
environments have structure the model doesn't: diurnal patterns in which devices
are present, access points that move, dual-band radios appearing as two BSSIDs.
The metric's shape is validated; the exact thresholds are not.

## Solar position

### Bug found

The first implementation passed both a fractional-day term and a separate
hour-of-day term into the local sidereal time calculation. Both encode time of
day, so they cancelled:

```
  2026-06-21 12:00 UTC (summer noon):   56.5 deg   expect ~57   OK
  2026-06-21 00:00 UTC (summer night):  56.5 deg   expect < 0   WRONG
```

Midnight and noon returned identical elevation. Fixed by computing GMST from the
fractional-day term alone.

### After the fix

Copenhagen, 55.68°N 12.57°E:

| Instant | Elevation | Expected |
|---|---|---|
| 2026-06-21 10:00 UTC (summer local noon) | 55.2° | high |
| 2026-06-21 00:00 UTC (summer night) | −10.2° | just below horizon |
| 2026-12-21 11:00 UTC (winter noon) | 10.9° | ~11° |
| 2026-12-21 23:00 UTC (winter night) | −57.7° | deep negative |
| 2026-03-20 05:00 UTC (equinox, near sunrise) | −2.6° | near 0 |

Equator at equinox noon UTC: **88.1°**, against a true 90°. Within ~2° at the
extreme, better than a degree elsewhere.

Sunrise and sunset by bisection on the elevation function, Copenhagen
2026-06-21:

```
  computed:  sunrise 04:26 local,  sunset 21:58 local
  almanac:   sunrise 04:26,        sunset 21:58
```

Exact to the minute. The bisection absorbs the equation-of-time error that the
closed-form hour angle leaves behind, which is why this is better than the
underlying approximation deserves.

## Compilation, and what it cost

It compiles now, and the history is worth keeping because the guesses in it were
wrong in an instructive way. The order the failures actually came in:

1. **Kotlin source.** One real error, in `VisionSensor.bind()`:
   `ProcessCameraProvider.getInstance()` returns a `ListenableFuture`, not a
   Play Services `Task`, so `kotlinx.coroutines.tasks.await` did not apply, type
   inference collapsed, and it took `unbindAll()` and `bindToLifecycle()` down
   with it. CameraX ships `ProcessCameraProvider.Companion.awaitInstance(Context)`
   for this, confirmed by grepping the constant pool of the published 1.6.2 AAR.
2. **Build DSL.** Kotlin 2.x removed `kotlinOptions`; `jvmTarget` moved to the
   `compilerOptions` DSL.
3. **Dependency metadata**, three rounds of it, ending with
   `lifecycle-runtime-compose-android:2.11.0` demanding `minCompileSdk=37` and
   AGP 9.1. It is not a direct dependency — `activity-compose` asks for 2.9.4
   and Compose UI for 2.8.7, and the sibling constraints on the directly pinned
   `lifecycle-*` artifacts aligned the whole family up to 2.11.0. Pinning the
   direct artifacts could not have fixed it. `tools/transitive_sweep.py` now
   resolves the entire runtime graph the way Gradle does and reads every AAR's
   `aar-metadata.properties`, so this class of failure is answerable in seconds
   instead of in four-minute CI cycles.
4. **Test sources**, which had never been compiled either: `assertEquals` with
   an `Int` expression where the `Double` overload was needed.

**MediaPipe was never the problem.** Every version of this document and the
README predicted that `tasks-audio` would be the breakage. `AudioSensor.kt`
produced zero errors. The real failure was in CameraX code nobody had flagged.
A reminder that a confident guess about which dependency will break is still a
guess.

## The APK, opened

CI had been producing a debug APK for a while before anyone looked inside one.
Doing so confirmed the things that are easy to assume and cheap to check:

| Claim | Found |
|---|---|
| manifest merges to what the build asks for | `compileSdkVersion 37`, `minSdkVersion 29`, `targetSdkVersion 36` |
| the app's own code is really packaged | all 11 classes present across 8 dex files |
| it degrades without the model | no `yamnet.tflite`, as CI has no copy |
| permissions are what the manifest declares | 18, plus one added by androidx |

It also turned up something nobody had noticed: `app/src/main/assets/README.md`,
instructions written for a human, was being packaged and shipped to devices.
Excluded now via `ignoreAssetsPatterns`.

The size is worth stating plainly, since nothing else in the repo does. The
debug APK is **about 178 MiB**:

| | |
|---|---|
| native libraries, 4 ABIs | ~126 MiB |
| dex | ~44 MiB |
| bundled ML Kit models | ~7 MiB |

That is ML Kit and MediaPipe shipping bundled models and native code for every
ABI, in a build with no minification. It is not a defect, but it makes
`adb install` slow, and a single-ABI build is much smaller.

## Not tested at all

Everything that needs real hardware:

- **The `ImageProxy` lifetime hack** in `VisionSensor.onFrame` holds the proxy
  open for 1.5 s on a background thread while ML Kit reads the planes. This is
  the single most suspect piece of code in the repo. If you see "ImageProxy
  closed" warnings, raise the delay; if you see frame starvation, restructure it
  to close the proxy from the completion of the ML Kit tasks instead.
- **Motion thresholds** in `BodySensor.classifyMotion` are reasoned from
  magnitudes, not measured. Watch `accel_rms` and `gyro_rms` while actually
  walking, driving and sitting, then retune.
- **Posture thresholds**, likewise.
- **Magnetic anomaly baseline** — `MAG_BASELINE_ALPHA = 0.002` is a guess at the
  right time constant.
- **Battery life.** The 4–8 hour figure is an estimate from what the camera
  typically costs, not a measurement.
- **Mic contention** between `AudioSensor` and `SpeechSensor`. The design opens
  and closes `AudioRecord` per sample specifically to avoid it, but this is
  exactly the kind of thing that behaves differently per vendor.
- **Vendor quirks.** MIUI/HyperOS is aggressive about background services. The
  foreground service should survive, but battery-optimisation exemption may be
  needed for long runs.

## Reproducing

The prototypes were throwaway Python. If you want to re-derive the numbers, the
three models are small enough to re-implement from the formulas above — that's
partly why they're written out in full rather than just asserted.
