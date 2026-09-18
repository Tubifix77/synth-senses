# Validation

What has actually been verified, what the verification caught, and what is still
untested. Written so you know which parts to trust.

## Summary

| Component | Status |
|---|---|
| `tools/receiver.py` | tested end to end against a simulated phone |
| `Habituation.kt` curve | prototyped and validated |
| `PlaceMemory.kt` coverage metric | prototyped, **failed**, redesigned, revalidated |
| `TempoSensor.kt` solar maths | prototyped, **bug found**, fixed, validated against almanac |
| All Kotlin | **never compiled** — no Android SDK available when written |
| On-device behaviour | **entirely untested** — thresholds are reasoned, not measured |

Two of the three algorithms had real bugs that testing caught. That is the
argument for the tests, and also the reason to be suspicious of the parts that
weren't tested.

## Receiver, end to end

A stand-in client performed a real WebSocket handshake, streamed percepts,
flushed a backlog, and responded to commands the way `SenseService.handle()`
would.

Verified:

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

## Not tested at all

Everything that needs real hardware:

- **Compilation.** No Android SDK was available. The most likely breakages are
  MediaPipe's `tasks-audio` builder API, which has moved between releases, and
  the dependency versions, written from memory.
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
