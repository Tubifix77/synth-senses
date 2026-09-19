# Integration spec

For whoever is building the consuming side. You should not need to read the
Android source, and you should not need to trust anything here that
[PROTOCOL.md](PROTOCOL.md) contradicts — that document is normative for the
wire format, this one is about how to consume it and what it means.

## What you are connecting to

An Android phone acting as sense organs. It samples camera, microphone, radio,
motion, its own internal state and the position of the sun, turns each sample
into a **percept**, and decides whether that percept is worth sending. You
receive the ones that survived that decision.

The decision is a habituation model, not a threshold. A stimulus seen
repeatedly stops being reported; time away restores it; something genuinely
new breaks through. This matters to you more than any field in the schema:

> **You are not seeing the world. You are seeing what changed.**

Silence means nothing new happened, not that nothing is happening. A phone
staring at an unchanging room sends almost nothing, by design.

## Status, so you build against what exists

| | |
|---|---|
| receiving percepts | **works, tested in CI** |
| the percept schema | **stable at version 2**, tested against the code |
| sending commands back | **not reachable from code yet.** See below. |
| curated "reports" rather than raw percepts | **does not exist yet** |

That third and fourth rows are deliberate. The shaping layer that would
summarise, budget and decide when to wake you is called the bridge, it lives
in *this* repository, and it is deferred until the app has run on real
hardware. See [ROADMAP.md](ROADMAP.md). Until then you consume percepts
directly, which is the honest interface rather than a promised one.

Nothing below is aspirational. If it is described here, it runs.

## Receiving

The receiver process writes **one JSON object per line on stdout**, and
everything meant for a human on stderr. So you are a Unix filter:

```bash
python3 tools/receiver.py | your-adapter
```

A complete consumer:

```python
import json, sys

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    p = json.loads(line)
    print(p["narration"], flush=True)
```

Guarantees on that stream:

- one percept per line, always valid JSON, never split across lines
- no prose, banners or diagnostics — those go to stderr
- no command replies or acknowledgements, only percepts
- the websocket frame envelope is stripped, so a percept looks identical
  whether it arrived live, in a backlog flush after a dropped connection, or
  by HTTP POST

### Or skip the receiver entirely

The phone will POST to a plain HTTP endpoint if its configured address starts
with `http://` or `https://`. The body is either one percept object or an
array of them, and it expects `200` with `{"accepted": n}`. If your stack
would rather listen for HTTP than read a pipe, that path is supported and
needs none of this repository at runtime.

The cost is that HTTP is one-way. The command channel only exists over the
websocket, so an HTTP integration can never steer the phone.

## What a percept is

Full field-by-field definitions are in [PROTOCOL.md](PROTOCOL.md). The shape:

```json
{
  "schema": 2,
  "device_id": "a3f9c1e07b2d",
  "seq": 412,
  "ts": "2026-09-19T18:04:11.230Z",
  "trigger": "salient",
  "attention": { "salience": 0.74, "novel": ["o:Person", "s:Speech"],
                 "known_tokens": 312, "dishabituated": false,
                 "floor_reason": null },
  "vision":  { "lens": "back", "brightness": 0.42, "labels": [...],
               "objects": [...], "text": null },
  "hearing": { "level_db": -38.2, "peak_db": -12.0, "events": [...],
               "transcript": null },
  "radio":   { "ble_count": 6, "place_id": "p3", "place_name": "desk", ... },
  "body":    { "motion": "still", "posture": "upright", "lux": 310.0, ... },
  "self":    { "battery": 0.62, "thermal": "NONE", "battery_temp_c": 33.1, ... },
  "tempo":   { "part_of_day": "evening", "is_daylight": false, ... },
  "gps": null,
  "narration": "I am in desk. ..."
}
```

### The fields that carry the most for a mind

| field | why |
|---|---|
| `narration` | deterministic prose over the same data, built for exactly this. Always present, never empty. Derived, so never authoritative — prefer the structured fields when precision matters. |
| `trigger` | *why* this was sent: `first`, `salient`, `heartbeat`, or `requested`. A `heartbeat` means nothing was interesting and the phone is proving it is alive. |
| `attention.salience` | 0 to 1, after habituation. Not "how interesting is the world" but "how unfamiliar is this to this phone right now". |
| `attention.novel` | the specific stimulus tokens that were new. Prefixed by modality: `v:` label, `o:` object, `s:` sound, `p:` place, `m:` motion, and so on. |
| `attention.floor_reason` | non-null when something overrode habituation: overheating, a bang, an unfamiliar place, critical battery. Treat as an interrupt. |
| `self.thermal`, `self.battery` | the phone's own body. Interoception, and the reason it might be about to stop. |
| `radio.place_id` | which learned place it thinks it is in. |

### Semantics that will catch you out

- **`null` means absent, never zero.** No barometer, or a denied permission,
  yields `null`. `"pressure_hpa": null` and `"pressure_hpa": 1013` are
  different facts and the distinction is deliberate. Never coerce to 0.
- **You see post-gate percepts only.** The phone samples far more often than
  it sends. Rate of arrival is itself a signal.
- **`place_id` is not a location.** It is a cluster of radio fingerprints the
  phone learned by itself. Ids like `p3` are local to that device, meaningless
  across devices, and not stable if someone issues `forget`. A place only has
  a `place_name` if something named it.
- **Identifiers are already hashed.** MAC addresses and BSSIDs are FNV-1a
  hashed to 10 hex characters before they enter a percept. You cannot recover
  the address, and you should not try.
- **`seq` restarts.** It counts posted percepts within one run of the sensing
  service and resets when that service restarts. It is not a durable unique
  id. Use `ts` for ordering and `(device_id, ts)` for identity.
- **`ts` is the phone's clock**, UTC, millisecond ISO-8601. After a
  reconnection a backlog is flushed, so arrival order can lag `ts` order.
  Sort by `ts` if order matters.
- **Multiple phones can connect at once.** Always key on `device_id`.
- **`schema` is 2.** Adding a nullable field will not bump it; removing or
  renaming one will. Ignore unknown fields rather than failing.

## Volume

Defaults, all adjustable from the app or by command:

| | |
|---|---|
| sample interval | 5 s, so at most 12 percepts per minute |
| salience threshold | 0.35 |
| heartbeat | 120 s, the longest silence you should see while it is running |

In practice habituation puts the real rate far below the ceiling: a static
scene goes quiet within roughly two minutes and then produces only heartbeats.
Budget for bursts when something happens, not for a steady stream.

## Sending commands back

**This does not work from code yet, and you should design for it anyway.**

The phone accepts eleven commands over the websocket and they round-trip
correctly. The gap is purely that the only thing which can currently send one
is a human typing at the receiver's console. There is no programmatic path.
See [ROADMAP.md](ROADMAP.md); this is the next thing to be built and it is
blocked on nothing.

The vocabulary, so you can design an intent model against it now:

| command | effect |
|---|---|
| `sample` | take a percept immediately |
| `look` | switch camera and sample, `{"lens": "front"\|"back"}` |
| `read` | run OCR on the current frame |
| `listen` | transcribe speech now |
| `speak` | say text out of the phone's speaker |
| `attend` | weight a whole modality, e.g. `{"modality": "s", "gain": 2.0}` |
| `set` | change interval, heartbeat, threshold, or toggle a sense |
| `places` | list learned places |
| `name_place` | give a learned place a name |
| `forget` | clear habituation, places, or both |
| `status` | full state dump |

The planned shape is symmetry with the outbound side: **commands in as JSON
lines on stdin**, the mirror of percepts out as JSON lines on stdout. The
receiver already refuses to start its console when stdin is a pipe,
specifically to leave that channel free. Full argument reference is in
[PROTOCOL.md](PROTOCOL.md).

Note that `speak` closes a physical loop. Whatever the phone says, its own
microphone will hear on the next sample and may transcribe back to you.
Decide deliberately whether that is a feedback bug or the point.

## What you inherit

The sensing side holds three lines and they become your responsibility the
moment a percept crosses to you:

- **Camera frames and audio buffers never leave the phone** and are never
  written to disk anywhere. Only derived labels, transcripts and numbers.
- **Raw identifiers never leave the phone.** Already handled by hashing.
- **`vision.text` and `hearing.transcript` are the sensitive fields.** OCR
  text is whatever the camera could read, which near a desk means documents
  and screens. A transcript is what a person in the room said, and they
  probably did not consent to your storage.

Whatever you build is now the weakest link for both. If you log percepts,
those two fields are the ones to think about first. `tools/digest.py` in this
repository redacts them by default for exactly this reason, and is worth
copying the posture from.

## Pointers

| | |
|---|---|
| [PROTOCOL.md](PROTOCOL.md) | normative wire format, every field, full command arguments |
| [ROADMAP.md](ROADMAP.md) | what is planned, in what order, and what is blocked on what |
| [VALIDATION.md](VALIDATION.md) | what has actually been measured, and what has not |
| `tools/receiver.py` | reference implementation of the receiving side, stdlib only |
| `tools/digest.py` | summarises a percept log; a worked example of consuming one |
