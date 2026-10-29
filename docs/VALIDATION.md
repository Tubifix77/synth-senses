# Validation

What has actually been verified, what the verification caught, and what is still
untested. Written so you know which parts to trust.

## Summary

| Component | Status |
|---|---|
| `tools/receiver.py` | **tested in CI** — RFC 6455 handshake, framing, backlog, HTTP fallback |
| `tools/transitive_sweep.py` | **tested in CI** — version ordering, ranges, ceiling detection |
| `tools/digest.py` | **tested in CI** — the computations, and that redaction holds |
| `docs/INTEGRATION.md` | **checked in CI** — its stated facts against the source |
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

## Battery, measured at last, 2026-10-21

An hour of continuous sensing on a Xiaomi 14T Pro (Android 16), all six senses
on, five-second interval, wifi connected:

| | |
|---|---|
| drain | **15.4 points/hour**, 40% to 25% |
| implied runtime | **~6.5 h from full** |
| battery temperature | 30.6 °C to **37.9 °C** |
| thermal status | `NONE` throughout — never throttled once |
| thermal headroom | 0.14 to 0.36, mean 0.26 |

The 4–8 hour figure this document carried was an estimate reasoned from what
the camera and radios cost. It lands inside its own estimate, which is worth
recording as much as the number is.

Two caveats before anyone treats 6.5 h as the answer. This was an hour
extrapolated, not a night observed. And it was a phone held, pocketed and
carried around a room, so the camera had real scene changes to process —
a phone lying still in one place will habituate and should cost less.

The 7.3 °C rise matters more for the **P20 Lite**, which is permanently on a
charger and cannot report thermal status at all below API 29. There,
`battery_temp_c` is the only heat signal there is.

## The same algorithm, properly fed, 2026-10-21

Later the same evening, on a clean place store with wifi contributing from the
first sample:

| | BLE only, earlier | BLE + WiFi |
|---|---|---|
| samples | 168 | 38 |
| new places minted | **32** | **0** |
| recognised | 92 | **38 of 38** |
| no place at all | 44 | 0 |
| similarity when recognised | bimodal at 0.0 and 1.0 | **graded, 0.57 to 1.00** |

**`PlaceMemory` was never broken.** It was starved, which is what the first
table measured and the opposite of what an hour of reading the coverage metric
suggested. Thirty-eight consecutive recognitions of one place, with a spread
of similarities rather than a switch between none and perfect.

Hearing worked in the same run, for the first time on any hardware:
`classifier` reported `ok` on all 38 percepts, no failures, and the vocabulary
discriminated rather than emitting one label — Speech 13, Silence 12, Typing 4,
Computer keyboard 4, and one each of child speech, a cough, a throat clear and
crumpling paper.

Habituation was gating as designed: **2.4 percepts a minute** against a
five-second interval that could produce twelve, so roughly 80% of what the
phone saw was suppressed as unremarkable.

Sixteen minutes, one room, one phone. Not a night, and not a second device.

## Place learning on real radio, 2026-10-21

The 96.5% room-separation figure below was measured against synthetic
fingerprints. The first evening on a real phone measured it against real
radio, and the difference is the whole finding.

A phone sitting still on one desk for about twenty minutes produced **38
places**. Split by whether WiFi was contributing to the fingerprint:

| fingerprint | samples | new places | recognised | no place at all |
|---|---|---|---|---|
| BLE only | 168 | **32** | 92 | 44 |
| BLE + WiFi | 44 | **1** | 43 | 0 |

With WiFi contributing roughly 21 hashed BSSIDs, similarity when recognised
was 0.91, 0.95, 0.96 and 1.00 — a graded spread. With BLE alone it was
bimodal at exactly 0.0 and exactly 1.0, and 44 samples had an entirely empty
fingerprint.

**The algorithm was not wrong, it was starved.** BLE in a home oscillates
between 0 and 9 visible devices between consecutive five-second samples, and
randomised MACs mean few of them persist. There is not enough evidence there
to recognise anything, so a new place is minted whenever anything at all is
seen. The bimodality was a symptom of that, not of the coverage metric: a
place holding one anchor scores a perfect 1.00 the moment that anchor
reappears.

An hour was spent diagnosing the metric before measuring its input. The
lesson generalises: **when a validated algorithm misbehaves on real data,
measure what it is being fed before touching what it does.**

That lesson applies to this section too, a day later. Its input was not only
sparse, it was **blind half the time**: BLE returns nothing with the screen
off, and the phone was being picked up and put down throughout. The "44
samples with an entirely empty fingerprint" and the oscillation between 0 and
9 devices on consecutive samples are at least partly the screen toggling and
the platform refusing every sixth scan, not the radio environment. Randomised
MACs are real too, so BLE-only place learning should be **re-measured** with
the screen-off fix before this paragraph's conclusion is trusted.

Two things follow, and neither is a flaw in the maths:

- **The defaults produce the starved case.** `ble` ships on and `wifi` ships
  off, so a fresh install learns places from the one radio that cannot
  sustain it. See `docs/ROADMAP.md`.
- **Orphaned places are never pruned.** The 23 places left with a single
  visit are permanent debris. A phone left in BLE-only mode for months would
  accumulate thousands, and nothing reclaims them or the id space.

### Deciding `still` without a per-device constant

The overnight run showed the problem; this is how it was fixed. Four candidate
detectors were replayed against the recorded `accel_rms` series of both phones
before any Kotlin was written, the way the place metric was prototyped.

Ground truth was chosen to be defensible rather than plentiful. **Still**: the
299 P20 samples from 23:02 to 07:27, on a charger, `face_up` throughout, with
nobody awake. **Moving**: any sample above `accel_rms` 0.6 on either phone,
which needs no hand labelling because a phone on a table does not reach it.

| detector | called still while untouched | correctly not-still while moving |
|---|---|---|
| `accel < 0.08` (what shipped) | **4.3%** | 100% |
| minimum-floor tracker | 3.3% | 100% |
| **median-floor × 1.4 + 0.02** | **100%** | 100% |
| recent-spread stability | 59.9% | 100% |

Three things that table settled:

- **The shipping constant fails on one of the two target devices**, at 4.3%.
- **The minimum is the wrong statistic.** It is the obvious way to track a
  noise floor and it is worse than doing nothing: the P20 dips to 0.01 now and
  then while its typical resting value is 0.17, so a minimum-tracker latches
  onto the outlier and calls the resting state `handled` permanently.
- **A stability test looked right and was not.** Since the resting band is
  extremely tight — 220 of 305 samples in just the two buckets 0.17 and 0.18 —
  judging by the spread of recent readings seemed the obviously
  device-independent answer. It scored 60%, because habituation makes the
  samples irregularly spaced and a fixed-length window spans a variable amount
  of time. It was proposed confidently and measured anyway.

Parameters are not finely tuned: window lengths of 60, 120 and 240 score
identically, and the cap does not bind on either device. The learned ceiling
settles at **0.224 on the P20 and 0.104 on the Xiaomi** — adapting by the
ratio their resting levels differ by.

**Rotation is still judged absolutely, deliberately.** A gyroscope measures
rotation rather than the noise of a spring, so it reads near zero at rest on
every device: across the 299 untouched P20 samples `gyro_rms` had median, 90th
percentile and maximum **all equal to 0.010**, while the same phone in a hand
reached 2.1. Adding `gyro < 0.04` to the median-floor lifted not-still
detection on the ambiguous setup window from 25.4% to 44.8% with still-recall
unchanged at 100%.

That 44.8% looks poor and is not. Of the 74 setup-window samples the detector
calls still, **zero** had `gyro_rms` at or above 0.04 — median 0.000, maximum
0.030. The detector never once disagrees with the rotation sensor. The
"handled" label on that window was wrong: the phone was on a desk for most of
it while its owner read messages. The same contamination, in the same window,
that had already produced one retracted finding.

Two guards, both for failure modes the data cannot show:

- **The ceiling never falls below the old 0.08.** A device quieter than the
  old constant keeps the old behaviour, so the change can only widen `still`,
  never narrow it. Nothing correctly still before becomes `handled` now.
- **The learned floor is capped at 0.25.** A phone carried in a pocket all day
  would otherwise raise its own median until walking read as resting.

`MotionFloorTest` encodes both phones' measured bands. `classifyMotion` was
pulled out as a pure function to make this testable at all — it was private
inside a class needing a `SensorManager`, so the single most device-dependent
decision in the app had never been unit-tested. The thresholds *above* `still`
are untouched and still unmeasured; the test pins them so a later measurement
is a deliberate change rather than a side effect.

### The first overnight run, 2026-10-21 23:02 to 07:27

A Huawei P20 Lite (ANE-LX1, Android 9, EMUI), on a charger, untouched, six
senses on, 5 s interval. **8.42 hours, 302 percepts.** The device is the
household emergency line and a retired handset, which is the realistic host
for this project rather than its awkward low end.

**The foreground service survived.** No gap longer than five minutes, no
sequence reset, no restart. This was the question the night was spent on, and
an aggressive vendor ROM leaving the service alone for eight hours is what
makes an always-on deployment viable at all.

**Nothing went quiet.** The failure mode that matters here is a sense that
works and then stops without throwing, so each was counted across the night
and across its thirds:

| sense | samples | first | last |
|---|---|---|---|
| vision labels | 304/304 | 23:02 | 07:27 |
| hearing level | 304/304 | 23:02 | 07:27 |
| wifi / cell / gps / steps | 304/304 | 23:02 | 07:27 |
| hearing events | 292/304 | 23:04 | 07:27 |
| ble | **0/304** | — | — |

That includes `VisionSensor.onFrame`'s `ImageProxy` lifetime hack, described
elsewhere in this file as the most suspect code in the repo. It produced
labels on every one of 304 samples over eight hours without stalling. Not
proof it is correct, but it is no longer the leading suspect.

**Correction, the next day: the BLE zero was a fault.** This paragraph first
said it was probably honest — that the soundbar and robot lawnmower seen that
evening sleep at night. Counting `ble_count` against `screen_on` across every
sample from both phones says otherwise:

| | screen on | screen off |
|---|---|---|
| P20 (Android 9) | BLE heard in **72%** of 105 | **0.4%** of 784 |
| Xiaomi (Android 16) | **77%** of 208 | **5%** of 115 |

Since Android 8.1 an unfiltered BLE scan returns nothing while the screen is
off, and `RadioSensor` passed no filter. The screen was off all night, so BLE
was blind all night, and the guess about sleeping devices was a plausible story
fitted to a wrong number. Reading AOSP's `ScanManager` for Android 9 through 15
found the way round it — one empty `ScanFilter` counts as filtered up to 14 —
and the same reading found a second source of fake zeros: more than five scan
starts in thirty seconds are refused outright, about one sample in six at the
5 s default. Both now publish `null`.

**Confirmed on the P20 after the fix:** 3 of 3 screen-off samples heard BLE,
against 0 of 29 on the build immediately before it. The behaviour read out of
Android 9's `ScanManager` holds on the hardware.

**Place learning was perfect, which validates two fixes at once.** One place,
`p2`, across all 303 samples. **Zero switches, zero new places, similarity
1.00 throughout.** Against the previous evening's 114 switches in 531 samples
on the Xiaomi, this is the containment guard and the evidence-based tie-break
doing exactly what they were written for. A stationary phone in a quiet room
is the easiest possible case, so this confirms the fixes rather than stressing
them.

**Attention behaved.** 226 heartbeats to 78 salient triggers, median salience
0.01. Three quarters of the night the habituation model had nothing to say,
which is correct for an empty dark room. What it did report was `Silence` 246
times, and `Breathing` and `Snoring` 26 times each — the app noticed someone
asleep.

#### The `still` threshold does not port between phones

The finding the night was actually for, and it needed a phone nobody was
touching to get it. `accel_rms` over 305 untouched samples:

| value | samples |
|---|---|
| 0.01–0.07 | 13 |
| 0.09–0.16 | 39 |
| **0.17–0.18** | **220** |
| 0.19–0.21 | 33 |

A sharp peak at **0.17–0.18**. `classifyMotion` calls something `still` only
below **0.08**, so the P20 reported **`handled` for 290 of 303 samples while
lying face up on a charger all night**. The Xiaomi idles at 0.04–0.05 and
reports `still` correctly. Same code, same situation, opposite answer.

So the published `motion` value — an ordinal on the wire, a dial in Potluck —
was wrong all night on one of the two target devices.

The fix is **not** to retune 0.08 to something that suits a Huawei: that
overfits the second phone exactly as it currently overfits the first. A
resting baseline has to be learned per device, the way the magnetic baseline
already is, so the question becomes "is this phone moving more than it usually
does" rather than "is this number under a constant chosen on someone else's
hardware".

Recorded honestly because the route to it was not clean: the same claim was
made the previous evening from 49 samples taken while the user was holding the
phone, retracted when they pointed that out — *"i am handling it to find
transcribe speech ofcourse it says handled"* — and is only now supportable
from eight hours of a phone on a table. **The retraction was correct when it
was made.** Being right later does not make contaminated evidence good.

#### −44 dBm is the top of the scale, not a signal

124 of the Xiaomi's 323 cell readings were **exactly −44 dBm**, on the same two
cells that otherwise read −102 to −112. A phone lying still does not swing 60 dB
— a millionfold in power — between samples. −44 is the ceiling of the LTE RSRP
range, and that modem returns it when it has no measurement. The P20's modem
never did: none of its 896 readings were −44, and every one sat in a
consistent −97 to −109.

So the fault is one vendor's modem, but the rule is not: a value pinned at the
top of its reporting range carries no information beyond "at least this", and a
phone indoors is never there. LTE and NR readings of −44 now publish null. The
same code also passed Android's documented `UNAVAILABLE` sentinel straight
through, which would have reached the wire as 2147483647 dBm; that is null
too. GSM and WCDMA have different ranges and no observed placeholder, so
nothing is assumed about them.

#### One fix, replayed for twenty hours

Every one of the P20's 895 percepts carrying a position, from 22:06 one evening
to 18:51 the next, held **the same fix** — identical latitude, longitude and
accuracy, **20.75 hours** unchanged. A live fix indoors moves by metres between
readings; this one never moved at all.

The app never asked for a location. It read `getLastKnownLocation`, which is
whatever another app last requested, and discarded the fix's timestamp. On a
phone nobody uses, nothing else asks, so the one fix obtained while the phone
was being set up was republished as current all night and all day — to Potluck
as well. It was only still right because the phone had not been moved.

Location is now requested while `location` is on, from the network provider
and GPS about once a minute, and every published fix carries `gps.age_s`. The
gps block had also never been documented in `PROTOCOL.md` — its sample percept
had `"gps": null` — and now is.

**On hardware it works, slowly.** The first install's first percept read
`gps.age_s` **160 971** — the borrowed fix was 44.7 hours old, older than the
log alone had shown — and four minutes later **161 166**, still ageing. That
was read here at first as "the phone is not sensing", with a GPS-only location
mode as the suspect. Both were wrong. `status` showed all three providers
switched on, and twenty minutes later the age was **530 s**: a fresh fix had
arrived about twelve minutes after the feed first asked. Nothing else on the
phone requests location, so it was this feed. Indoors, under a floor, a first
network fix took about twelve minutes; the four-minute check was simply too
early to see it.

Measured with `location_fixes` on the next start: the first fix arrived after
about six minutes, and then **0 → 1 → 2 → 4 → 6** across the following seven
— about one a minute, which is the rate the feed asks for. So the steady state
is right and only the first fix is slow, six to twelve minutes indoors.

**An unexplained crash, recorded rather than guessed at.** The build that
added those diagnostics dropped its connection twice on its first start — the
receiver shows hello, "peer closed", hello, "peer closed", then a stable link —
and recovered on its own. The next build put the newest crash report into
`status`, and it came back **null**: `SynthSensesApp` writes a report for any
uncaught Kotlin or Java exception, and wrote none. So it was not an exception in
this code. What remains is a crash in native code — the ML libraries are
native, and those bypass the handler — or the system killing the process.
Android 9 offers no way to ask which; `getHistoricalProcessExitReasons` arrived
in Android 11. It has not recurred. If it does, the receiver's connect log is
the evidence, and the timing — first start after an update — is the lead.

#### Seven times Earth's field, and a precise compass bearing

`body.magnetic_ut` on the P20 overnight was **347–352 µT**. Earth's field is
22–67 µT anywhere on its surface. Across everything recorded:

| | samples | median magnitude | outside 20–75 µT |
|---|---|---|---|
| Xiaomi | 323 | 48 µT | 1 |
| P20 | 901 | **351 µT** | **802** |

The sensor is not the problem: in the 99 P20 samples that *were* in Earth's
range — in the user's hand, elsewhere in the house — it read a normal 43 µT.
Its resting spot by the charger has a strong local magnetic source, a speaker
or a mount, and it spends most of its life there.

`heading_deg` comes from the rotation vector, which takes its yaw from the
magnetometer. In a field that dominated by something local the bearing points
at the magnet, and it was published as a precise number anyway: the narration
said "facing south-west" 579 times, in a direction that meant nothing. It is
now null when the field magnitude is outside 20–75 µT — a physical bound, not
a device constant, which is why it can be fixed without a per-phone
calibration. `magnetic_ut` stays on the wire and says why.

#### On a black frame, the labeller still answers

All night `vision.brightness` was 0.00 — the camera faced a dark ceiling — and
every one of those frames came back labelled **Sky, Monochrome, Space**. The
narration repeated it, so the phone said "I can see sky, monochrome, space" in
a pitch-dark room, sometimes in the same sentence as "it is dark". Across every
frame from both phones:

| brightness | frames | top labels |
|---|---|---|
| 0.00 | 749 | Sky 749, Monochrome 749, Space 749 |
| below 0.05 | 248 | the same three, 248 each |
| below 0.10 | 116 | Sky 116, Space 107, Monochrome 74 |
| below 0.20 | 26 | Sky 24, Monochrome 16, Space 11, two others |
| 0.20 and up | 454 | Musical instrument, Desk, Room, … — real variety |

Below 0.10 the output is the same three words essentially every time: the
labeller's response to an empty image, not to a scene. Above 0.20 it varies the
way a real room does. The cut is at 0.10, the conservative end — only where the
evidence says the answer is the black-frame signature in nearly every case — so
the 26 frames between 0.10 and 0.20, two of which may have held something, are
still labelled.

Unlike BLE, an empty list is the truthful answer here rather than a null: the
camera *did* look, and nothing was recognisable. `vision.brightness` is still
published and says why. It also stops the labeller, the object detector and
OCR all running on nothing, which on an always-on phone is every dark hour.

#### `current_ma` is wrong by a factor of 1000 on this device

`SelfSensor` divides `BATTERY_PROPERTY_CURRENT_NOW` by 1000, documented by
Android as microamps. Huawei reports milliamps. Overnight the field ranged
**−0.64 to +1.01 mA**, which for a phone drawing from a wall charger is
impossible; the true figures are about −640 mA and +1010 mA. The value is
plausible enough to be believed and wrong by three orders of magnitude, which
is worse than reporting nothing.

#### On a charger, the phone holds its charge — and `charging` said otherwise

Battery ranged **0.64 to 0.82** across the night and ended at 81%, so **power
is not a constraint for this deployment.** The shape of it is not wear. Laid
out at each change of the `charging` flag it is a perfectly regular cycle:

| time | battery | `charging` | current |
|---|---|---|---|
| 00:10 | 81% | true → false | −140 mA |
| 01:10 | 82% | false → true | +420 mA |
| 01:34 | 81% | true → false | −140 mA |
| 02:40 | 82% | false → true | +440 mA |

and so on every ~88 minutes until morning. That is EMUI's battery protection
holding the cell near 80%: plugged in throughout, it tops up, stops, lets it
drift, and tops up again.

The defect was ours. `charging` came from `BatteryManager.isCharging`, which
follows that charge-hold logic rather than the plug, so a phone on the charger
all night reported `charging: false` for **182 of 299 samples**. It was not a
good current signal either — it agreed with the direction of `current_ma` only
55% of the time. `self.charging` is documented as being fed, so it now comes
from `EXTRA_PLUGGED`, which does not move when the plug does not; the sign of
`current_ma` still carries whether current is flowing in right now.

Measured before claiming a cost: the flapping did **not** waste attention. Of
73 salient percepts overnight, exactly one had `chg:` as its novelty, and that
was 23:05, the real moment the phone was plugged in. Habituation absorbed every
later flip. The harm was a wrong published value, nothing more.

#### The ears were on the close-talk input, and nothing could tell

`AudioSensor` requested `UNPROCESSED` unconditionally, with a fallback to
`MIC` in a `catch`. Android documents that `UNPROCESSED` "behaves like DEFAULT"
on a phone without it, silently, so the fallback could never run, and nothing
reported which input was in use. Asked on 2026-10-22, after `status` gained
`mic_source`, **the P20 does not declare
`PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`**: every hearing sample before
`6a9a57e` came from its ordinary input, processed for a mouth near the mic.
It now records from `VOICE_RECOGNITION`, which the CDD requires to run
without noise reduction or gain control (5.4.2).

Not yet measured: whether that hears a television better. The only "before"
is two readings from the interval-tally build, one `quiet` and one `speech`
in about five minutes, against three `Speech` votes in the first four
percepts after the switch. That is suggestive, not a comparison, and the
level readings are not comparable across the change at all.

The TV session itself ran that evening, 20:50–21:10 UTC, with the companion in
ai-consciousness polling the port. All four of its polls read `speech`, at
−35.96 to −44.9 dB, and none read `music`. Four readings, one room, one
programme: the TV is audible to the new input, which is all this shows. The
companion still described the room as quiet, which was its own prompt's
doing and has been fixed there, not here. The question
came from Tue asking whether speakerphone mode would hear the room better.
The instinct was right, though not that mechanism: speakerphone is the call
input, which suppresses background noise on purpose.

### One thin scan mints a duplicate of the room you are in

Found later the same evening, with WiFi on and the starvation gone. The phone
held exactly two places and alternated between them while lying still on a
charger: **114 switches across 531 samples**, GPS identical to seven decimal
places throughout, and both places reporting a similarity of exactly 1.00.

The place table said how the second one was born:

| place | created | visits | anchors | stable |
|---|---|---|---|---|
| p1 | 17:30 | 870 | 80 | 20 |
| p2 | **18:43** | 63 | 41 | 18 |

p2 was minted an hour after p1, in the same room, while p1 already had
hundreds of visits and twenty stable anchors. The percept log has the exact
sample: coverage fell from 0.76 to **0.49** against a `RECOGNISE` of 0.50 on a
scan that saw **10 access points where the previous one saw 14 and the usual
count is 18**. One momentarily thin scan, one permanent duplicate.

**Coverage cannot detect this, because it only measures recall.** It asks how
much of the place is visible. It never asks the complementary question — how
much of what is visible is the place — so a scan showing a *subset* of the
current room is indistinguishable from a scan showing a different room. The
fix adds that second direction as `containment`, the share of the visible
weight that a candidate place accounts for, and refuses to mint when it is
high: most of what I can see belongs to a place I know, so I am standing in
it under a degraded scan rather than standing somewhere new.

The threshold was measured rather than chosen, over 400 seeds, against the
three cases that have to stay apart:

| case | containment (min / mean / max) | required |
|---|---|---|
| degraded scan of the current room | 1.000 / 1.000 / 1.000 | suppress |
| adjacent room, 4 shared APs at −18 dB | 0.069 / 0.196 / 0.307 | mint |
| one stray passing device | 0.000 | mint |
| an unrelated 25-AP environment | 0.000 | mint |

`CONTAINED = 0.5` sits in the gap between 0.307 and 1.000. The separation is
not marginal, and it does not depend on the seed.

Two details that matter more than they look:

- **A suppressed scan must not count as a visit.** Persistence is
  `seen / visits`, so bumping `visits` without observing divides every
  anchor's persistence by a larger number. A run of bad scans would slowly
  dissolve the very room it had just failed to recognise. A scan too thin to
  act on changes nothing at all.
- **A single stray device must still mint**, and does — it shares nothing
  with the room, so containment is 0. That case is already pinned by
  `a single stray device cannot match a known place`, and it is the reason
  the guard could not simply be "the fingerprint is small".

### One unprovisioned value starved the other eleven

Not found here. The **Potluck Desk** session noticed it from the other end,
watching the hub: `Synth Senses` was making about one write a minute — writes
382 to 383 over a minute — and none of them was landing, while the receiver
was up, the phone connected and `percepts.jsonl` written seconds earlier.

Three things had to line up, and all three were mine:

1. `Pending.take()` is oldest-written-first, keyed on
   `written_at.get(name, 0)`. A value that has **never** been written has no
   entry, so it defaults to 0 and sorts to the *head of the queue by
   construction*. `lat` was unprovisioned, so it was taken first on every
   single pass.
2. `write_value` returns a 60-second hold for a 404, and `_drain` applied it
   with `budget.hold_off(hold)` — but the budget gates **every** path, not the
   one that failed.
3. `_drain` ended the pass with `return` on any failure, so nothing behind the
   failing value was ever reached.

So each pass took `lat`, 404'd, stood the whole writer down for a minute and
returned. One write a minute, exactly as measured, and none of the other
eleven values ever went out.

**The 404-keeps-the-value fix of 3e4953f is what made this permanent.** That
fix was right — a value never written successfully is not in `written`, so
refresh-before-stale can never re-offer it, and dropping a 404 meant `lat`
would wait for the reading itself to change, which for a stationary phone is
never. But before it, `rejected()` dropped the value and the queue moved on.
Keeping it was correct; retrying it from the head of a queue that stopped at
the first failure was not. **A fix that removes a drop has to say where the
retry goes.**

Holds are now per path, on `Pending`, and a failure no longer ends the pass.
The budget keeps exactly one job: the program-wide rate limit. That division
is the real lesson — a 404, a 403 and a 504 are facts about *one resource*,
while a 429 is a fact about *this program*, and one shared backoff cannot
represent both.

Two checks in the suite had asserted the broken behaviour, reading "a 404
stops the flood after one attempt" and "holds off before trying again". It
sounded protective. What it measured was the queue being abandoned.

### The cap hid a tie, and insertion order broke it

The same log exposed a second, independent defect. `coverage` is
`min(1, achieved / expected)` where `expected = Σ w·p` and persistence
`p ≤ 1`, so **any place whose visible fraction exceeds its own mean
persistence scores above 1 and is capped to exactly 1.00.** In a room with
twenty stable anchors that happens constantly, and once two places covered one
room, both read 1.00 on nearly every sample.

The selection loop was `if (c > bestCoverage)`. A strict `>` over an exact tie
keeps whichever place the `LinkedHashMap` happened to hold first, so the
reported id was decided by **which place was learned first**, and flipped
between p1 and p2 as the filtered anchor sets shifted. A consumer watching the
`place` dial saw the room change with the phone motionless.

Ties are now broken on `achieved` — the absolute weight of corroborating
evidence — so the better-established place wins and the answer does not
oscillate. The cap stays: it is what keeps a place from being punished for
having unusually persistent anchors. Only the tie-break was wrong.

A place seen once has `stable_anchors: 0` by construction — `MIN_SEEN` is 2,
so after one visit every anchor sits at `seen = 1` however many there are.
One orphan held 20 anchors and 0 stable purely because it was new. That is
what the `isLearning()` window exists for, and reading `stable 0` as a defect
is a mistake the receiver briefly encouraged.

CI had been producing a debug APK for a while before anyone looked inside one.
Doing so confirmed the things that are easy to assume and cheap to check:

| Claim | Found |
|---|---|
| manifest merges to what the build asks for | `compileSdkVersion 37`, `minSdkVersion 28`, `targetSdkVersion 36` |
| the app's own code is really packaged | 369 references to `Lnet/synthsenses/` across 8 dex files |
| it degrades without the model | no `yamnet.tflite` was packaged, as CI had no copy — **changed on 2026-10-21**: CI now fetches the model and verifies its hash, and `apk_facts.py --require` fails the build if it is missing |
| permissions are what the manifest declares | 19 — the 18 declared, plus `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` added by androidx |
| the foreground service declares the types it uses | `200 = location\|camera\|microphone` |
| it still ships for every ABI | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |

**This is no longer a one-off.** It was, and it decayed within the day:
lowering `minSdk` to 28 made the row above stale the same evening it was
written, and the table said so rather than being quietly wrong.
[`tools/apk_facts.py`](../tools/apk_facts.py) now reads these facts out of the
binary manifest — no `aapt`, no Android SDK, pure stdlib — and **CI runs it
against every APK it builds**, failing the job if `minSdk`, `targetSdk` or
`compileSdk` drift from the expected values or if `assets/README.md` or a
model file reappears in the package.

Writing it turned up one thing worth recording, because it is the kind of bug
that reports success: `attributeStart` in a compiled XML node counts from the
attribute extension struct, 16 bytes into the node, not from the node itself.
Off by those 16, every attribute name resolves to a garbage string-pool index
— which does not throw, it reads as *"this element has no attributes"*. The
first run reported a manifest with no package, no SDK versions and no
permissions, which is indistinguishable from a tool that works and an APK that
is empty.

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
- **The discovery beacon reaching a phone.** Both halves are tested — the
  packet's shape, the broadcast targets, and that none of it reaches stdout, in
  `test_receiver_ws.py`; the parsing and the URL rules in `EndpointTest`. What
  nothing here can test is the one hop between them. A phone's WiFi driver
  filters broadcast traffic in power save, which is what the `MulticastLock` in
  `Discovery.listen` is for, and plenty of home routers and almost every guest
  network drop subnet broadcasts outright. So **Find receiver is an
  optimisation, never the only way in**: the Endpoint field is prefilled with
  the phone's own `/24` and can still be typed into, and the receiver still
  prints the address on startup. If Find comes back with "nothing answered" on
  a network where the receiver is plainly running, that is the hop to suspect,
  not the protocol.
- **`Discovery.localIPv4` on anything but WiFi.** It reads `LinkProperties` off
  whatever `ConnectivityManager` calls the active network, so USB tethering,
  ethernet over a dongle and a VPN should all work and none has been tried.
  A phone on mobile data will prefill a carrier-side `/24` that no receiver is
  on, which is harmless — it is a prefill, not a connection — but it will look
  odd.

## Reproducing

The prototypes were throwaway Python. If you want to re-derive the numbers, the
three models are small enough to re-implement from the formulas above — that's
partly why they're written out in full rather than just asserted.
