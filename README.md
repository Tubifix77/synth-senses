<h1 align="center">Synth Senses</h1>

<p align="center">
  <em>Turn an Android phone into the eyes, ears and inner ear of an AI running somewhere else.</em>
</p>

<p align="center">
  <a href="https://github.com/Tubifix77/synth-senses/actions/workflows/build.yml"><img alt="build" src="https://github.com/Tubifix77/synth-senses/actions/workflows/build.yml/badge.svg"></a>
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%2010%2B-3DDC84">
  <img alt="language" src="https://img.shields.io/badge/kotlin-2.4-7F52FF">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-blue">
  <img alt="schema" src="https://img.shields.io/badge/percept%20schema-v2-orange">
  <img alt="status" src="https://img.shields.io/badge/status-builds%20%C2%B7%20untested%20on%20hardware-yellow">
</p>

---

The phone samples the world on-device, turns each sample into a structured
*percept*, and streams it over a two-way WebSocket to a box you control — which
can talk back and steer what the phone pays attention to.

Everything interpretive runs locally on the NPU/GPU. No cloud vision APIs. The
only traffic on the wire is percepts out and commands in.

```
  camera ──▶ ML Kit: labels, tracked objects, OCR ───┐
  mic ─────▶ YAMNet: 521 sound classes ──────────────┤
             SpeechRecognizer (offline) ─────────────┤
  BLE ─────▶ device count, named devices ────────────┤
  WiFi ────▶ BSSID fingerprint ──────────────────────┼──▶ Percept ──▶ habituation ──▶ ws://your-box
  sensors ─▶ motion, posture, steps, field, pressure ┤                    │              │
  system ──▶ thermal, battery temp, memory, power ───┤              salience gate    commands
  clock ───▶ solar elevation, sun times, part of day ┘                                   │
                                                                                         ▼
                                              look · read · listen · speak · attend · set ·
                                              places · name_place · forget · status · sample
```

## Contents

- [Status](#status)
- [Quick start](#quick-start)
- [What it senses](#what-it-senses)
- [Habituation](#habituation)
- [Place learning](#place-learning)
- [Steering it from your box](#steering-it-from-your-box)
- [Permissions](#permissions)
- [Battery](#battery)
- [Privacy](#privacy)
- [Docs](#docs)

## Status

It builds. CI assembles a debug APK, passes Android Lint, and runs 25 JVM unit
tests on every push; all four jobs are green.

What that does and does not buy you:

- **Verified.** It compiles and packages. The three algorithms behave the way
  [docs/VALIDATION.md](docs/VALIDATION.md) describes — this time measured
  against the shipped Kotlin rather than a Python model of it.
- **Verified, and worth the trouble.** The first run of those tests found a bug
  no model of the algorithm could have found: `PlaceMemory` could not recognise
  a place a second time, because a place created moments ago fails its own
  persistence filter. Every scan minted a new place. Eighteen visits to one
  room produced eighteen rooms.
- **Not verified.** Anything needing hardware. No percept has ever come off a
  real phone. Sensor thresholds are reasoned from magnitudes rather than
  measured, and the `ImageProxy` lifetime in `VisionSensor.onFrame` remains the
  most suspect code here.
- `tools/receiver.py` is tested end to end against a simulated phone —
  handshake, percept stream, backlog flush, and all eleven commands
  round-tripping with replies.

So trust it to build, and to gate attention sensibly. Do not trust a number
that came off a sensor until you have watched that sensor yourself.

## Quick start

```bash
git clone https://github.com/Tubifix77/synth-senses.git
cd synth-senses
```

Open in Android Studio (Ladybug or newer), let Gradle sync, Run. Or headless,
with a device attached:

```bash
./gradlew installDebug
```

> **First clone:** the Gradle wrapper JAR isn't committed. Android Studio
> generates it on sync, or run `gradle wrapper` once if you have Gradle installed.

Then start the test receiver — stdlib only, no `pip install`:

```bash
python3 tools/receiver.py
```

Set the app's endpoint to `ws://<your-machine-ip>:8077/link`, press **Start**, and
type commands at the receiver prompt:

```
sample              look front         places
read                speak hello there  name p3 kitchen
listen              status             forget habituation
```

Wire your own AI in at `handle_percept()` in `tools/receiver.py`.

### One manual step

Sound classification needs YAMNet, which isn't redistributed here. Drop it at
`app/src/main/assets/yamnet.tflite` — see
[`app/src/main/assets/README.md`](app/src/main/assets/README.md).

Without it the app still runs: you keep loudness, peak and speech transcription
but lose the 521 sound labels. It degrades quietly and reports `"events": []`.

## What it senses

| Block | Contents |
|---|---|
| `vision` | image labels, tracked objects with stable IDs, OCR, frame brightness |
| `hearing` | loudness, peak, 521 YAMNet sound classes, offline speech transcript |
| `radio` | BLE device count and named devices, WiFi BSSID fingerprint, cell info, learned place |
| `body` | motion state, posture, accel/gyro RMS, steps, heading, lux, magnetic anomaly, pressure rate |
| `self` | thermal status, battery temperature, current draw, voltage, memory and storage pressure |
| `tempo` | local time, part of day, solar elevation, sunrise/sunset, day length |

Plus `narration`: a deterministic English sentence built on-device from the
structured data, so your AI can read prose without needing a vision model at its
end.

> *"I am in the kitchen. Almost nothing else is broadcasting nearby. In ordinary
> indoor light. I can see a room, furniture; one person. Text reads "FIRE EXIT". I
> hear speech — someone said "are you still recording". I am still, lying face up,
> facing south. It is afternoon on Friday, 14:03."*

Full schema in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Habituation

Posting a percept every five seconds forever buries the receiving AI in "still
the same room". A fixed novelty threshold helps, but it has no memory: a room
you've stared at for six hours is exactly as interesting as one you entered a
minute ago.

Instead, every stimulus token carries a trace that saturates with exposure and
decays with time away. Salience is how un-habituated the current stimulus set is.

```
  exposure   1: salience 1.00      gap    0 min -> 0.01
  exposure   3: salience 0.67      gap    5 min -> 0.12
  exposure  10: salience 0.17      gap   20 min -> 0.37
  exposure  20: salience 0.03      gap   45 min -> 0.64
  exposure  60: salience 0.01      gap  120 min -> 0.93
```

A static scene goes quiet in about two minutes and becomes worth mentioning
again after a couple of hours away. A person walking into that habituated room
scores 0.74 immediately, because `o:Person` and `s:Speech` are novel tokens.

Three refinements that matter:

- **Dishabituation.** A startling stimulus halves every trace, so the next sample
  re-reports the whole scene rather than just the bang. Real nervous systems do
  this, and it's the behaviour you want.
- **Intensity floors.** Thermal SEVERE, battery under 8%, a sudden loud sound, an
  unfamiliar place, any speech heard — these set a salience floor regardless of
  habituation, with the reason in `attention.floor_reason`. Habituating to your
  own house fire would be a design flaw.
- **Traces persist to disk.** Habituation *is* memory; it shouldn't reset because
  the service restarted.

The token vocabulary in `Percept.tokens()` decides what counts as "the same thing
twice", and it's the main thing to tune.

## Place learning

The phone learns places from radio fingerprints — hashed WiFi BSSIDs and strong
BLE MACs with signal strengths. Raw addresses never leave the device.

The obvious approach, comparing two fingerprints for overlap, **does not work**.
Simulated against realistic conditions it splits one room into a dozen "new
places" the moment anchors drop out of a scan, and BLE-only mode fails almost
entirely because randomised MACs swamp the comparison.

What works is asking a different question — not *do these two readings match* but
*how much of what this place normally shows me can I see right now*:

```
  expected = Σ (signal weight × persistence)  over the place's anchors
  achieved = Σ  signal weight                 over anchors visible now
  coverage = achieved / expected
```

Persistence is the fraction of visits an anchor has appeared on, learned per
place. Validated over 12 simulated revisits each:

| Scenario | Mean coverage | Recognised |
|---|---|---|
| Quiet room, light drift | 0.97 | 12/12 |
| Dense environment, 60% of anchors drop | 0.66 | 8/12 |
| BLE only, 3 fixed + 4 random per visit | 1.00 | 12/12 |
| BLE only, 2 fixed + 6 random per visit | 1.00 | 12/12 |
| Adjacent room sharing 4 access points | 0.41 | correctly new |
| Unrelated 40-device scan | 0.00 | correctly new |

Places start unnamed. The phone discovers that a place exists; your AI names it
with `name_place`. That division of labour is the interesting part.

## Steering it from your box

Once the box can talk back, the phone stops being a broadcaster and becomes an
organ with directable attention.

| Command | Effect |
|---|---|
| `{"cmd":"sample"}` | take a percept now |
| `{"cmd":"look","lens":"front"}` | switch eyes and sample |
| `{"cmd":"read"}` | force OCR this sample |
| `{"cmd":"listen"}` | force speech transcription now |
| `{"cmd":"speak","text":"..."}` | say it out loud through the phone |
| `{"cmd":"attend","modality":"s","gain":2.0}` | weight a token prefix |
| `{"cmd":"set","interval_ms":15000}` | change rhythm, thresholds, any sense on/off |
| `{"cmd":"places"}` | list learned places |
| `{"cmd":"name_place","id":"p3","name":"kitchen"}` | name one |
| `{"cmd":"forget","what":"habituation"}` | make the world new again |
| `{"cmd":"status"}` | full state, including what it has stopped noticing |

`attend v 0.2` is roughly "stop caring about the walls". Full details and prefix
list in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Permissions

| Sense | Needs | Notes |
|---|---|---|
| Vision | `CAMERA` | foreground service type `camera` |
| Hearing, speech | `RECORD_AUDIO` | type `microphone` |
| **BLE** | `BLUETOOTH_SCAN` | declared `neverForLocation` — **no location needed** |
| **WiFi scan** | `ACCESS_FINE_LOCATION` | plus location services on, device-wide |
| Cell info | `ACCESS_FINE_LOCATION` | same |
| Steps | `ACTIVITY_RECOGNITION` | Android 10+ |
| Sun times | coarse location | falls back to clock-only without it |
| Interoception, body | none | free |

`WifiManager.startScan()` is throttled to 4 calls per 2 minutes. `RadioSensor`
respects that and reads cached results in between, which costs nothing.

## Battery

Roughly 4-8 hours continuous with vision on; much longer without. Levers,
cheapest first:

1. Raise the sample interval. 30 s is plenty for ambient awareness.
2. Turn vision off. Radio + body + self is very cheap and still rich.
3. `KEEP_CAMERA_WARM = false` in `VisionSensor.kt` unbinds between samples.
4. BLE is already `SCAN_MODE_LOW_POWER`.

An old phone on a charger in a corner makes a genuinely good sensor node.

## Privacy

Frames and audio buffers are never written to disk and never leave the device —
only derived labels, transcripts, hashed radio IDs and numbers. MACs and BSSIDs
are hashed before they go anywhere.

Two things to know:

- The spool at `/data/data/net.synthsenses/files/spool.jsonl` holds undelivered
  percepts, transcripts included, in plaintext until they're sent.
- If you enable both voice and speech transcription, anything the box says
  through the phone gets heard and transcribed back on the next sample. That loop
  is either a bug or the most interesting thing here, depending on what your box
  does with it.

Use `wss://` if the link leaves your LAN. **Tell people in the room.** See
[SECURITY.md](SECURITY.md).

## Docs

| | |
|---|---|
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | percept schema, frame types, full command reference |
| [docs/VALIDATION.md](docs/VALIDATION.md) | what was tested, what the tests caught |
| [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md) | dependencies and their licences |
| [CHANGELOG.md](CHANGELOG.md) | version history |
| [CONTRIBUTING.md](CONTRIBUTING.md) | how to work on this |

## Licence

MIT — see [LICENSE](LICENSE). Note that the ML models it loads have their own
terms; see [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).
