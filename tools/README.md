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
