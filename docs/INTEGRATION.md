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
| sending commands back | JSON lines in on the receiver's stdin. See below. |
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
- **`heading_deg` is not where the camera is looking.** It is the azimuth of
  the phone's **top edge** — its +Y axis, projected onto the horizontal. The
  back camera looks out along **−Z**, which is orthogonal to that, so the two
  never describe the same direction. Lying face up on a desk, the heading is
  meaningful while the camera points straight down at the surface; held
  upright so the camera sees the horizon, the azimuth becomes unreliable
  because `getOrientation()` degrades as pitch approaches vertical. **Do not
  infer that a seen label lies in the heading's direction.** Use
  `vision.lens`, which says which camera produced the labels, and `look front`
  / `look back` to choose.
- **Vision labels are posture-dependent and can be confidently wrong.**
  Observed on real hardware: a phone face up on a desk reported *"sky,
  underwater"* with the labeller's confidence threshold at 0.55, because the
  back camera was looking at a dark featureless surface and a generic labeller
  reads that as open water. The light sensor is on the front, so `lux` can
  read as a normally lit room while the camera sees nothing meaningful at all.
  Habituation absorbs the repetition within minutes, so these stop being
  reported as novel — but do not read a single narration literally.
- **Identifiers are already hashed, but device *names* are not.** MAC
  addresses and BSSIDs are FNV-1a hashed to 10 hex characters before they
  enter a percept, and you cannot recover the address. `ble_named[].name` is
  the name the device broadcasts, passed through as-is — usually a product
  ("Ultra Slim Soundbar"), sometimes a person ("Anna's AirPods"). Treat that
  field as personal data even though the id beside it is not.
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

Percepts leave the receiver as JSON lines on stdout. **Commands go back in as
JSON lines on its stdin**, which makes the whole sensing side an ordinary
bidirectional filter — no sockets, no ports, no second protocol.

```
{"cmd": "look", "lens": "front"}
{"cmd": "attend", "modality": "s", "gain": 2.0}
```

One object per line. The receiver reads stdin as commands whenever stdin is
not a terminal; when it is, you get the human console instead, and `look front`
typed by a person does the same thing. `--no-console` turns both off.

**The phone's answers come back on stdout, mixed in with the percepts, and the
rule for telling them apart is absolute: a reply always carries `type`, a
percept never does.** The receiver strips the transport envelope from every
percept precisely so that stays true however it arrived.

```python
for line in receiver.stdout:
    msg = json.loads(line)
    if "type" in msg:
        handle_reply(msg)      # ack, status, places, hello, error
    else:
        handle_percept(msg)
```

Replies are off unless something is actually driving the receiver, so a plain
`receiver | consumer` pipeline gets the stream it always had.

A command naming something outside the vocabulary below is refused by the
receiver and never reaches the phone; you get
`{"type": "error", "error": "unknown command", ...}` instead. So are malformed
lines. A command sent while no phone is connected returns
`{"type": "error", "error": "no node connected", "cmd": ...}` rather than
vanishing, which is the difference between a bridge that waits forever and one
that knows to retry.

**Wiring it up.** A shell pipeline only runs one way, so the practical shape is
to run the receiver as a child process and talk to both its pipes:

```python
proc = subprocess.Popen(
    [sys.executable, "tools/receiver.py"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE,
    text=True, bufsize=1, encoding="utf-8", errors="replace",
)
proc.stdin.write(json.dumps({"cmd": "look", "lens": "front"}) + "\n")
proc.stdin.flush()
```

**Pass `encoding="utf-8"` and mean it.** A narration or a transcript can hold
any character at all, and on Windows a pipe defaults to the locale codec — so
without it the stream dies on the first word someone says that cannot be
spelled in cp1252. The receiver forces UTF-8 on its own side; anything reading
it has to say so too.

The phone accepts eleven commands over the websocket, and the receiver
forwards only these — `tools/test_docs.py` checks that whitelist against the
app's own constants, so this table cannot drift from what the phone has.

The vocabulary, so you can design an intent model against it:

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
