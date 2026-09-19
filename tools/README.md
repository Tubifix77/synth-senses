# tools

## receiver.py

A two-way percept receiver. **Standard library only** — no `pip install`, no
dependencies to audit. The WebSocket server is implemented directly against
RFC 6455 for that reason.

```bash
python3 receiver.py                        # ws + http on :8077
python3 receiver.py --port 9000 --token s3cret
python3 receiver.py --no-console           # for CI or as a daemon
python3 receiver.py --log ''               # disable JSONL logging
```

Point the app at `ws://<this-machine-ip>:8077/link`. Percepts stream in with a
salience bar; type commands at the prompt:

```
sample                  take a percept now
look front              switch eyes and sample
read                    OCR whatever is in frame
listen                  transcribe speech now
speak hello there       talk out of the phone
attend s 2.0            double the weight of sound tokens
attend                  clear all gains
set interval_ms 15000   change the rhythm
places                  list learned places
name p3 kitchen         name a learned place
forget habituation      make the world new again
status                  full state dump
quit
```

It also accepts plain HTTP POST on the same port, so it works with an `http://`
endpoint too — just without the command console being any use.

### Wiring in your own AI

Pipe it. Percepts leave on **stdout, one JSON object per line**; everything
written for a person leaves on **stderr**. So the receiver composes with
anything, and integrating does not mean editing this file:

```bash
python3 receiver.py | your-ai
python3 receiver.py | jq -r 'select(.attention.salience > 0.7) | .narration'
python3 receiver.py 2>/dev/null | tee percepts.jsonl | your-ai
```

Every line is a percept and nothing else. The websocket wraps a single percept
in a frame carrying `"type": "percept"`; that envelope is stripped, so a
consumer never has to care whether a percept arrived over the websocket, inside
a backlog flush, or by HTTP POST. Command replies never appear on stdout.

See [`../docs/PROTOCOL.md`](../docs/PROTOCOL.md) for every field. The quick
version: `p["narration"]` is prose ready to feed an LLM, and everything else is
there when you want precision.

| | |
|---|---|
| default | JSON lines when stdout is **not** a terminal, readable view when it is |
| `--jsonl` / `--no-jsonl` | force it either way |
| `--quiet` | never render the human view |
| `--pretty` | render it even while streaming JSON lines |
| `--log ''` | stop also writing `percepts.jsonl`, if you are piping to `tee` |

If you would rather stay in-process, `handle_percept(p, node)` is still called
once per percept and is still yours to replace. It renders to stderr, and it
cannot break the transport: if it raises, the percept has already been logged
and already left on stdout, and the sender still gets its response.

`handle_reply(msg, node)` handles `ack`, `places`, `status` and `hello` frames.

### Notes

- Percepts are appended to `percepts.jsonl` by default (gitignored).
- Multiple nodes can connect at once; commands typed at the prompt broadcast to
  all of them. `node` identifies the sender.
- No auth unless you pass `--token`. It's a development tool — don't expose it to
  the internet.

## transitive_sweep.py

Answers one question before a CI cycle is spent on it: **would
`:app:checkDebugAarMetadata` reject anything in this build?**

Standard library only. It resolves the whole debug runtime graph the way Gradle
does — module metadata, BOM platforms, dependency constraints,
highest-version-wins iterated to a fixed point — then reads each AAR's
`META-INF/com/android/build/gradle/aar-metadata.properties` over HTTP range
requests, so a 30 MB artifact costs a few KB. Exit code 1 means CI would fail.

```bash
python3 tools/transitive_sweep.py                    # check the current pins
python3 tools/transitive_sweep.py --set com.squareup.okhttp3:okhttp=5.5.0
python3 tools/transitive_sweep.py --sdk 36 --agp 8.13.2   # a what-if
python3 tools/transitive_sweep.py --all              # list jars too
```

compileSdk, minSdk and the AGP version are read out of the build files, so it
cannot drift out of date with the project. Pass `--sdk` / `--agp` only to ask a
hypothetical.

**Run it before any dependency change.** Checking only the direct dependency
list is what let `lifecycle-runtime-compose-android` through and cost several
four-minute CI cycles; that artifact is never named in the build file.

Downloads are cached in `.mvncache/` next to the script, which is gitignored.

## digest.py

Turns a percept log into something you can read, or hand to someone else.

```bash
python3 digest.py percepts.jsonl
python3 digest.py percepts.jsonl --raw     # includes OCR text and transcripts
cat percepts.jsonl | python3 digest.py -
```

It exists for the hardware test. [`../docs/ROADMAP.md`](../docs/ROADMAP.md)
lists what a phone has to tell us and none of it is answerable by scrolling
JSONL: whether the camera produced anything, whether habituation actually
gated, how fast the battery went, whether the service stalled. In particular
it breaks `accel_rms` down **per motion state**, which is exactly the
measurement `BodySensor`'s guessed thresholds need.

**OCR text and speech transcripts are redacted by default**, and reported as
counts. That is a privacy control, not a formatting choice: OCR text is
whatever the camera could read, which on a desk means documents and screens,
and a transcript is what someone in the room said. `--raw` includes them,
deliberately. There is a test whose whole job is to assert they do not leak.

## The tests

All three run in CI on every push, and all are stdlib-only.

| | |
|---|---|
| `test_receiver_ws.py` | speaks RFC 6455 at `receiver.py` as a real client: masked frames, a fragmented message, backlog, ping, close, the stdout/stderr contract, and that a malformed percept cannot break the transport |
| `test_transitive_sweep.py` | the sweep's pure logic — Gradle version ordering, version ranges, AGP tuples, ceiling detection |
| `test_digest.py` | that the digest computes what a hardware test needs, and above all that redaction holds |

```bash
python3 tools/test_receiver_ws.py
python3 tools/test_transitive_sweep.py
python3 tools/test_digest.py
```
