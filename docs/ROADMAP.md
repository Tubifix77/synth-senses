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

### The phone, and the range below it

The device this will first run on is a **Xiaomi 14T Pro** — Android 14, so API
34, on HyperOS. Two consequences worth holding separately from the general
case:

- It takes the API 31+ path everywhere one exists, so `BLUETOOTH_SCAN` works
  without location, and `thermal_headroom` (API 30+) is available. Neither is
  degraded on this phone.
- **An aggressive vendor ROM is exactly the overnight-survival risk listed
  above**, not a hypothetical. HyperOS is one of the more assertive ones about
  killing background work.

The brief is to use whatever a modern phone offers while still degrading
cleanly on an old one, so `minSdk` is **28**. That is not the same as designing
for 28: a sensor or API that only exists higher up should be used where it
exists and reported absent where it does not, which is what the
degrade-never-fabricate invariant already requires.

### The second phone, which is the harder test

A **Huawei P20 Lite on Android 9** — API 28, which is why the floor moved. It
earns its place by being a worse environment than the Xiaomi in every way that
matters:

- **It is a household emergency phone.** Fixed in place, permanently on
  charge, sound always up, and the only number some of the family have. That
  makes one requirement absolute and above any sensing quality: **the app must
  never cost that phone a call.** The ears already opened the mic in short
  bursts and released it in a `finally`, which made a collision unlikely;
  `micIsBusy` now makes it impossible, standing down from the first ring rather
  than from the moment someone answers. Verify it anyway on the first evening —
  ring the phone from another one while the service runs.
- **It cannot report its own heat.** Thermal status is API 29 and headroom is
  API 30, so both degrade to absent here. `self.battery_temp_c` still works and
  is the only heat signal this phone has. On a device that is permanently
  charging, expect it to read warm from the charging alone, and read it as a
  trend rather than against a fixed number.
- **EMUI 9 is at least as aggressive as HyperOS** about killing background
  work, so the overnight-survival question is sharper here than on the Xiaomi.
- **It may have no gyroscope.** Several sources say so; `status` settles it.
  Without one, `gyro_rms` is zero forever and `classifyMotion` loses the
  rotation evidence that separates "handled" from "still" and "vehicle" from
  "walking". Motion still works off the accelerometer, more coarsely.

What it is unusually good at is the thing this project is actually for. Fixed
in one place with constant motion, posture and place, almost everything it
reports is habituated away within the hour — so whatever is left is the room's
own life. It is close to a pure test of whether the attention model earns its
keep.

What that brief needs and did not have is a way to ask the phone what it has.
Spec sheets disagree about this device's sensors — a barometer is the open
question — and this repo has a rule against settling such things from a sheet.
So **`status` now reports `device` and `sensors`**: model, Android version, and
each sensor the body sense uses with the hardware's own name for it. Type it
first; it costs one line and replaces the guessing.

### What the emulator can and cannot settle

CI runs the instrumented tests at **API 28 and API 36** — minSdk and targetSdk,
the two ends the app is actually developed for. It cannot measure anything on
the list above; it answers "does this start, and does it survive a rotation",
which is the question worth not spending a scarce evening with a real phone on.

It earns its place. The API 28 entry failed on its first run, and the cause was
a real drift rather than the emulator: `MainActivityLaunchTest` restated the
permission list instead of deriving it, so when a BLE scan below API 31 started
requiring fine location, the test stopped granting everything the app asks for
and the activity sat behind a dialog it never dismissed. That had been wrong
for API 28, 29 and 30 for as long as the fix had existed, and was invisible
while the only level ever run was 35.

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

**Blocked on nothing — and now done.** Making the phone steerable from code.
It was fully specified by [PROTOCOL.md](PROTOCOL.md) already, depended on no
decision the other project has to make, and was a prerequisite for every
version of the bridge, so it was the piece to build before the hardware
arrived. It is built; see below.

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

### The return path, which was the interesting half

**Done.** It was the one piece blocked on nothing, and it is built.

It used to read: commands that steer the phone can only be typed at the
receiver's console, which needs a terminal on stdin, so a downstream process
has no way to send one — the mind could watch, but not look. Directable
attention is what makes this an organ rather than a webcam, and half of it was
unreachable from code.

The shape is the symmetry the design was left open for: **percepts out as JSON
lines on stdout, commands in as JSON lines on stdin.** The console had always
declined to start when stdin was not a terminal, precisely to keep that channel
free. No sockets, no ports, no second protocol — the sensing side is now an
ordinary bidirectional filter.

```
{"cmd": "look", "lens": "front"}
```

What came with it, because a one-way path is not actually usable:

- **Answers come back.** The phone's `ack`, `status`, `places` and `hello`
  replies go out on stdout alongside the percepts. They are told apart by one
  absolute rule — **a reply always carries `type`; a percept never does** —
  which holds because `deliver` strips the envelope from every percept however
  it arrived. Off unless something is driving the receiver, so a plain
  `receiver | consumer` pipeline is unchanged.
- **Refusals are explicit.** A command outside the vocabulary is rejected by
  the receiver and never reaches the phone. A command sent with nothing
  connected answers `{"type": "error", "error": "no node connected"}` rather
  than vanishing — the difference between a bridge that retries and one that
  waits forever.
- **The shorthand still works from a pipe**, so `echo status | receiver.py`
  does what it obviously should.

A FIFO, a Unix socket or a second port were the alternatives. stdin was tried
first and was enough. In practice a consumer runs the receiver as a child
process and talks to both pipes, because a shell pipeline only runs one way;
[INTEGRATION.md](INTEGRATION.md) has that snippet.

**One trap, found while testing this rather than reasoned about.** Anything
reading the receiver's stdout must pass `encoding="utf-8"`. A narration or a
transcript can hold any character, and on Windows a pipe defaults to the locale
codec, so the stream dies on the first word someone says that cp1252 cannot
spell. The receiver now forces UTF-8 on its own streams, and so do the tools
that print percept text — but a consumer has to say so on its side too. It
never reproduces in CI, which is Linux and UTF-8 already.

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

Two of the ten carry a caveat that is not visible in the table, both of the
same shape as the ordinal problem and neither of them a bug:

- **`place` numbers are stable until someone resets them, and then they are
  not.** `PlaceMemory` hands out `p1`, `p2`, … in the order rooms are first
  seen, and persists the counter, so `p3` keeps meaning the same room across
  restarts. `forget {"what":"places"}` sets it back to 1, and the next rooms
  learned take those numbers in whatever order they are walked into. A board
  captioning "place 3" would go on captioning it, meaning somewhere else. The
  command is typed deliberately, so this is a consequence to know rather than a
  trap to defend against — but the board's place labels should be read as
  provisional, and re-checked after a reset. There is no fix available on the
  Potluck side: `place_name` exists here, and a name is a string, which is the
  one thing an 8-byte value cannot carry.
- **`steps` counts from the last reboot, not from install.** That is
  `TYPE_STEP_COUNTER`'s contract, so the value drops to zero when the phone
  restarts, and anything differencing it sees a large negative. The percept
  also carries `body.steps_delta`, already clamped non-negative, which is the
  reboot-safe alternative — but it covers one sample interval, which is five
  seconds, and is close to meaningless as a standing current value. Publishing
  the cumulative count and expecting the reset is the better of the two.

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

**The bridge exists.** [`tools/potluck_bridge.py`](../tools/potluck_bridge.py),
stdlib only like the rest of the tooling:

```bash
python3 tools/receiver.py | python3 tools/potluck_bridge.py --dry-run
python3 tools/potluck_bridge.py --manifest     # the inbox block to paste
```

It is built and tested against a cell that does not exist yet, which is
possible precisely because an unprovisioned write answers 404 and that is a
state rather than an error. `--dry-run` prints what it would send; `--manifest`
prints the `builtin:inbox` block and the `outside` entries so the cluster file
is never hand-typed from this table and cannot drift from it.

What it does, beyond the mapping:

- **Writes on change, not on a timer**, with a per-value epsilon so a salience
  of 0.70 followed by 0.71 is not news. Same reasoning as bucketing in
  `Percept.tokens()`; here the cost of pretending otherwise is the write
  budget rather than habituation.
- **Refreshes at half of `stale_ms`** so a quiet room never reads STALE. This
  source is habituation-gated, so silence is the normal case and must not look
  like a dead program.
- **Keeps under the ceiling deliberately.** Potluck allows two writes a second
  with bursts of four; the bridge runs at 1.2 and leaves the burst as headroom
  rather than as the plan.
- **Pins the hub's certificate** to the one it paired with, so the token never
  travels to a different hub. Pairing stores the fingerprint beside the token.

A bug worth recording, because it was the kind that only shows under the
condition the system is designed for: the first version drained the write
queue only when a percept arrived. A burst of changes followed by silence
would have left the last values pending forever — and silence is exactly what
habituation produces. Reading and writing now run on separate clocks.

**What specifying it already bought.** Writing this up surfaced a defect that
breaks BLE scanning, and with it place learning, on every phone between minSdk
29 and API 30: the app asked for `BLUETOOTH_SCAN`, which does not exist there,
and never asked for the fine location a BLE scan needs instead. `ble_count` was
a permanent zero, the fingerprint never went non-empty, and
`PlaceMemory.recognise` was not reached once. Fixed and pinned.

Being accurate about the blast radius, since an earlier version of this
paragraph was not: the phone this will first run on is an **Android 14**
device, which takes the API 31+ path and was never affected. The fix protects
the older range the project still claims to support, and the bug would have
been found the hard way by whoever first installed it there.

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
