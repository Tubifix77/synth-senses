# Roadmap

What happens next, in order, and what is deliberately not being started yet.

## 1. First, a phone

Nothing below should begin until the app has run on real hardware for a decent
stretch. Everything still unverified in this project is hardware-shaped, and no
amount of further design work substitutes for thirty minutes with a device and
`adb logcat`:

- the `ImageProxy` lifetime in `VisionSensor.onFrame`, which holds the proxy
  open for a fixed 1.5 s and is the most suspect code here
- the motion and posture thresholds in `BodySensor`, reasoned from magnitudes
  rather than measured
- `MAG_BASELINE_ALPHA`, a guess at a time constant
- mic contention between `AudioSensor` and `SpeechSensor`
- battery life, currently an estimate
- whether the foreground service survives the night on an aggressive vendor ROM

You need **platform-tools**, roughly 15 MB, not the 2 GB SDK. `adb logcat` is
the part you cannot do without, because the suspect code fails by logging
warnings rather than by crashing. A phone that looks like it is working is not
evidence.

See [VALIDATION.md](VALIDATION.md) for the full list of unmeasured constants.

### Reading the test

Point the phone at the receiver, let it run, then:

```bash
python3 tools/digest.py percepts.jsonl
```

That answers the list above directly rather than by scrolling JSONL. It breaks
`accel_rms` down per motion state, which is the measurement the `BodySensor`
thresholds need; reports battery movement as points per hour; and flags gaps
over three minutes, which is what a killed service looks like.

It **redacts OCR text and transcripts by default**, so its output is safe to
paste somewhere or hand to an assistant. `--raw` includes them once you have
decided that is fine.

## 2. Then, the bridge

**Context.** This project is the sensory apparatus for a separate project of
the author's, on machine consciousness. This one produces percepts; that one is
meant to have the experience. The bridge is the piece between them and it does
not exist yet.

### Where it lives: here, mostly

The bridge belongs in **this** repository, with a small input adapter on the
other side. Almost all of its work is sensor-domain knowledge: what a report
should contain, what is worth waking the mind for, how much to send, and how
to turn an intent back into a command. That last one is pure
[PROTOCOL.md](PROTOCOL.md) and has no business living anywhere else. The mind
should never need to know what a lux bucket is or that `attend s 2.0` doubles
the weight of sound tokens.

It also puts iteration where the tooling is. Retooling what gets sent becomes
a change here, against a repo that already has the schema, the fixtures, the
wire-shape test and CI.

**But the seam between the two halves must be a neutral documented format, not
a function call.** This repo must not import the other project's client
library or learn its API, because the dependency would then point from the
stable side to the experimental one: the percept schema is versioned,
documented and tested, while an early consciousness project's input format
will churn weekly, and every churn would drag this repo along with it. Emit
reports as JSON lines and let the adapter over there read them. Then neither
side knows the other's internals and either can be rewritten alone.

So, concretely:

```
phone ──ws──▶ receiver ──percepts──▶ bridge ──reports──▶ adapter ──▶ mind
                  ▲                     │
                  └──── commands ───────┘◀── intents ────┘
```

Everything from `receiver` to `bridge` is this repo. `adapter` is the small
piece over there, and it should stay small enough to rewrite in an afternoon.

[INTEGRATION.md](INTEGRATION.md) is the spec for whoever writes that adapter.
It describes what exists today rather than what is planned, so it can be coded
against now: percepts arrive as JSON lines, here is what the fields mean, here
is what will catch you out, and here is the command vocabulary to design an
intent model against even though nothing can send one yet.

### Two halves, and only one of them is blocked

The deferral applies to the half that needs answers this project does not
have. It is worth separating them, because one can start immediately.

**Blocked on the phone, and on the questions below.** What a report contains,
how much of it, and when to send it. Designing that against a stream nobody
has seen real output from is guesswork, and one evening of watching actual
percepts arrive will say more than any amount of speculation now.

**Blocked on nothing.** Making the phone steerable from code. The return path
is fully specified by [PROTOCOL.md](PROTOCOL.md) already, depends on no
decision the other project has to make, and is a prerequisite for every
version of the bridge. See what is missing, below. This is the piece to build
first if you want to move before the hardware arrives.

### Fill this in before starting

Nothing here should be guessed at. The bridge's shape depends entirely on
answers this repository does not have:

| | |
|---|---|
| the other project | name, where it lives, what language and runtime |
| its input | text? structured events? a function call? a queue? |
| its loop | does it run continuously, or is it invoked and does it return? |
| its memory | does it keep history itself, or expect to be given context? |
| its cadence | does it want every percept, or to be woken when something matters? |

### What is already done

The outbound half is solved and tested. The receiver emits percepts on stdout
as one JSON object per line, one percept per line, with the transport envelope
stripped so it does not matter whether a percept arrived over the websocket, in
a backlog flush, or by HTTP POST. Everything written for a person goes to
stderr. So the bridge is a program that reads JSON lines on stdin, and the
whole of the sensing side is already a Unix filter:

```bash
python3 tools/receiver.py | the-bridge | the-mind
```

`narration` exists precisely for this: deterministic on-device prose, ready to
feed a language model, with the structured blocks alongside when precision
matters.

### What is missing, and it is the interesting half

**The return path is human-only.** Commands that steer the phone — `look`,
`listen`, `read`, `attend`, `speak`, `sample` — can currently only be typed at
the receiver's console, which reads stdin and only starts when stdin is a
terminal. A downstream process has no way to send one. Today the mind could
watch, but not look.

That matters more than it sounds. Directable attention is the thing that makes
this an organ rather than a webcam, and half of it is unreachable from code.

The natural shape, and the one the current design was left open for, is
symmetry: **percepts out as JSON lines on stdout, commands in as JSON lines on
stdin.** The receiver already declines to start the console when stdin is not a
terminal, precisely so that path stays free. Then the bridge is an ordinary
bidirectional filter and needs no sockets, no ports and no new protocol; the
command vocabulary in [PROTOCOL.md](PROTOCOL.md) is already defined and already
round-trips.

Worth considering instead, if that proves awkward: a control FIFO, a Unix
socket, or a second port. All three are heavier. Try stdin first.

### Things that will bite

- **Context budget.** Habituation already suppresses the repetitive, which is
  most of the work, but an attention model tuned for "is this worth saying" is
  not the same as one tuned for "does this fit in a context window". The bridge
  will probably need its own floor, or to summarise.
- **Turn-taking.** Percepts arrive whenever the world does something. Most
  model loops are request and response. Something has to decide when to wake
  the mind, and that decision belongs in the bridge, not in the phone.
- **Who owns memory.** `Habituation` and `PlaceMemory` are on the phone and are
  about *stimulus* familiarity. They are not episodic memory and should not be
  made into it. If the mind needs to know what happened yesterday, that is the
  mind's memory, not the sensor's.
- **The loop is real.** `speak` sends text out of the phone's speaker, and the
  next hearing sample will transcribe it back. That feedback path is either a
  bug or the most interesting thing here, and the bridge is where you decide
  which.
- **Privacy crosses the bridge.** Transcripts of what people said near the
  phone leave the device. The sensing side hashes identifiers and never sends
  frames or audio; whatever is on the other side inherits the obligation, and
  its storage is now the weakest link.

## 3. Alongside: Potluck, a second consumer

Specified, agreed on the Potluck side, **not approved here and not yet run**.
Recorded because it otherwise exists only in messages between two sessions.

Potluck is a separate project of the author's: a distributed runtime that
federates ESP32-S3 boards, and optionally a PC, into one addressable machine
whose boards pool their sensors. A phone with a camera, a microphone, a
barometer and three radios is a richer node than any board in that cell, which
is why it fits.

It consumes percepts; it cannot consume them whole. A Potluck value is at most
**8 bytes** with a closed unit enum, and its write API takes "a JSON bool or
number". Labels, transcripts and the narration have no representation there and
stay on this side. What crosses is ten numbers.

| output | type | unit | from |
|---|---|---|---|
| `salience` | f32 | ratio | `attention.salience` |
| `motion` | u32 | — | `body.motion`, 0 still … 3 vehicle |
| `posture` | u32 | — | `body.posture`, 0 face_up … 5 unknown |
| `steps` | u64 | count | `body.steps` |
| `sound` | u32 | — | curated from `hearing.events`, 0 quiet … 5 other |
| `loudness` | f32 | — | `hearing.level_db`, dBFS |
| `place` | u32 | — | `radio.place_id`, `p3` → 3 |
| `battery` | f32 | ratio | `self.battery` |
| `battery_temp` | f32 | celsius | `self.battery_temp_c` |
| `thermal` | u32 | — | `self.thermal`, 0 none … 6 shutdown, absent if UNKNOWN |

Where a number stands for a label, it is the index into the matching list in
[`Vocabulary.kt`](../app/src/main/java/net/synthsenses/senses/Vocabulary.kt).
That order is now a wire contract that no percept ever shows: insert a state in
the middle of `MOTION` and a board starts captioning "walking" while the phone
is in a car, with nothing thrown and nothing logged. `VocabularyTest` pins the
lists and `tools/test_docs.py` checks the ranges above against them, so the
rule is **append, never insert**.

Writing this table is what caught the first instance: `motion` was specified
here as a five-value enum ending in `unclear`, and `PROTOCOL.md` listed an
`unknown` that `classifyMotion` cannot return. Four values, and now tested.

They live at `potluck://lab/act/phone/<name>`, written by a **PC-side bridge**
reading `receiver.py`'s stdout — not by the phone, which would need TLS, cert
pinning and pairing implemented in Kotlin for no gain. The bridge pairs as
**"Synth Senses"** with the `write` right, and that exact string must match the
`writers` entry in their cluster file.

Four constraints the bridge has to honour, three of which come from Potluck's
model rather than ours:

- **Never replay the backlog.** The phone spools up to 2000 undelivered
  percepts and flushes them on reconnect. Potluck holds a current value with an
  age, not a series, so replaying publishes stale readings as current and
  destroys the write budget. Fast-forward to the newest and drop the rest.
- **Newest-wins per path, not a queue of writes.** Same reason. It also bounds
  the queue at ten entries: ten values against 2 writes/s takes five seconds to
  drain, and the default sample interval is five seconds, so an append-only
  queue would never catch up.
- **`stale_ms` of 300000**, about 2.5× the heartbeat. This source is
  habituation-gated and a quiet room produces only heartbeats, so a short
  staleness bound would mark a perfectly healthy phone STALE almost always.
- **404 is a state, not an error.** Writes return 404 until the resource is
  provisioned, so the bridge can be built and exercised before any of this is
  live.
- **Print a 403's `result` text verbatim, do not summarise it.** Potluck's hub
  diagnoses the one failure that otherwise looks like a bug in this project:
  re-pairing under a name already taken yields "Synth Senses 2", while the
  cluster file still names "Synth Senses", so a bridge that is paired, holds a
  valid token and looks entirely correct has every write refused. The 403 names
  both strings and says to revoke and pair again. Requested by Potluck, and
  cheap insurance against an evening lost to it.

One genuine point of fit. Potluck's `NO_DATA` is exactly right for a sensor the
phone does not have: a value that is simply never written says "absent" rather
than "zero", which is this project's first invariant expressed in someone
else's type system.

**What specifying it already bought.** Writing this up is what surfaced the
Android 10 BLE defect — `ble_count` would have published a confident zero and
`place` would have been permanently absent, because `PlaceMemory.recognise` was
never reached on that phone. Fixed and pinned before anything shipped.

## 4. Not planned

Splitting the receiver into its own repository. The Unix shape that matters is
already there: one producer, a documented text protocol, newline-delimited
JSON, a reference consumer. Splitting now costs the wire-shape test that keeps
[PROTOCOL.md](PROTOCOL.md) honest and buys version skew.

The stated trigger was "a second consumer or a second producer", and **Potluck
is now that second consumer**, so the decision has been revisited rather than
left to rot. The answer is still no. Both consumers read the same JSON lines,
the percept format did not have to change for either, and the Potluck bridge is
a small program rather than a fork of the receiver. What a split would cost is
unchanged. Revisit again if a consumer ever needs the wire format to change for
its sake — that, rather than the count of consumers, is the thing that would
actually argue for separating them.
