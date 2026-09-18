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

Replace the body of `handle_percept(p, node)`. It's called once per percept,
including each one in a backlog flush. `p` is the parsed percept dict —
see [`../docs/PROTOCOL.md`](../docs/PROTOCOL.md) for every field.

The quick version: `p["narration"]` is prose ready to feed an LLM, and everything
else is there when you want precision.

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

## The tests

Both run in CI on every push, and both are stdlib-only.

| | |
|---|---|
| `test_receiver_ws.py` | speaks RFC 6455 at `receiver.py` as a real client: masked frames, a fragmented message, backlog, ping, close, and what reached the log |
| `test_transitive_sweep.py` | the sweep's pure logic — Gradle version ordering, version ranges, AGP tuples, ceiling detection |

```bash
python3 tools/test_receiver_ws.py
python3 tools/test_transitive_sweep.py
```
