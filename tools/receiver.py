#!/usr/bin/env python3
"""
Two-way percept receiver for Synth Senses v0.2 — stdlib only, no pip install.

    python3 receiver.py                      # ws + http on :8077
    python3 receiver.py --port 9000 --token s3cret

Then in the app set the endpoint to  ws://<this-machine-ip>:8077/link

Percepts stream in. Type commands at the prompt to steer the phone's attention:

    sample                  take a percept right now
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

Wire your AI in with a pipe. Percepts leave on stdout as JSON lines, one
percept per line; everything written for a person leaves on stderr. So:

    python3 receiver.py | jq -r .narration
    python3 receiver.py | your-ai
    python3 receiver.py 2>/dev/null | tee percepts.jsonl | your-ai

JSONL is on by default when stdout is not a terminal, and off when it is, so
running it bare still gives you the readable live view. Force either with
--jsonl / --no-jsonl. handle_percept() is still there for in-process use.
"""

import argparse
import builtins
import base64
import hashlib
import json
import os
import socket
import struct
import sys
import threading
from datetime import datetime

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
TOKEN = None
LOG = None
STREAM = False          # percepts as JSON lines on stdout
PROSE = True            # the human rendering, on stderr
clients = []
clients_lock = threading.Lock()
out_lock = threading.Lock()


def say(*args, **kwargs):
    """Anything meant for a person. Always stderr, so stdout stays pipeable."""
    kwargs["file"] = sys.stderr
    builtins.print(*args, **kwargs)
    sys.stderr.flush()


def emit(p):
    """One percept, one line, on stdout. This is the integration point."""
    if not STREAM:
        return
    line = json.dumps(p, ensure_ascii=False)
    with out_lock:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


def deliver(p, node):
    """Every arriving percept goes through here, whichever transport brought it.

    The websocket wraps a single percept in a frame envelope carrying
    "type": "percept"; the HTTP body and the percepts inside a backlog do not.
    Strip it here rather than at each call site, so the guarantee is
    unconditional: every line of the stream is a percept and nothing else, no
    matter which way it arrived or what the client chose to include.
    """
    if "type" in p:
        p = {k: v for k, v in p.items() if k != "type"}
    log_percept(p)
    emit(p)
    if PROSE:
        # The renderer is a convenience. If it trips over an unexpected shape,
        # that must not reach the transport: the percept is already logged and
        # already on stdout, and the sender is still owed its response.
        try:
            handle_percept(p, node)
        except Exception as e:
            say(f"  (could not render percept #{p.get('seq')}: "
                f"{type(e).__name__}: {e})")


# ----------------------------------------------------------------- your hook

def handle_percept(p, node):
    """Render one percept for a person, on stderr.

    This used to be the integration point, which meant integrating by editing
    a source file. Prefer the pipe: percepts leave on stdout as JSON lines.
    Edit this only if you want the in-process hook.
    """
    a = p.get("attention") or {}
    v = p.get("vision") or {}
    h = p.get("hearing") or {}
    r = p.get("radio") or {}
    b = p.get("body") or {}
    s = p.get("self") or {}
    t = p.get("tempo") or {}

    sal = a.get("salience")
    sal = sal if isinstance(sal, (int, float)) else None
    bar = "#" * int(round((sal or 0) * 20))
    stamp = datetime.now().strftime("%H:%M:%S")
    # Nothing here may assume a field is present or well-typed. This renders
    # whatever arrives off a network socket, and a missing key is a display
    # problem, not a reason to drop a percept.
    trigger = str(p.get("trigger") or "?")

    say(f"\n[{stamp}] #{p.get('seq')} {trigger:9s} "
          f"salience {sal:.2f} |{bar:<20}|" if sal is not None else
          f"\n[{stamp}] #{p.get('seq')} {trigger:9s} salience    ? |{bar:<20}|")
    say(f"  {p.get('narration','')}")

    facts = []
    if v: facts.append("sees " + (", ".join(l["name"] for l in v.get("labels", [])) or "—"))
    if h: facts.append("hears " + (", ".join(e["name"] for e in h.get("events", [])) or "—"))
    if r.get("place_id"):
        facts.append(f"place {r.get('place_name') or r['place_id']}"
                     f" ({r.get('place_similarity',0):.2f}"
                     f"{', NEW' if r.get('place_is_new') else ''})")
    if r: facts.append(f"ble {r.get('ble_count')} wifi {r.get('wifi_count')}")
    if b: facts.append(f"{b.get('motion')}/{b.get('posture')}")
    if s: facts.append(f"{s.get('thermal')} {int((s.get('battery') or 0)*100)}%"
                       f"{'+' if s.get('charging') else ''}"
                       + (f" {s.get('battery_temp_c')}C" if s.get('battery_temp_c') else ""))
    if t: facts.append(t.get("part_of_day", ""))
    say("  " + " · ".join(x for x in facts if x))

    if a.get("novel"):
        say(f"  novel: {', '.join(a['novel'][:6])}")
    if a.get("floor_reason"):
        say(f"  floor: {a['floor_reason']}")
    if h.get("transcript"):
        say(f'  said: "{h["transcript"]}"')


def handle_reply(msg, node):
    kind = msg.get("type")
    if kind == "ack":
        say(f"  <- ack {msg.get('cmd')}: "
              f"{'ok' if msg.get('ok') else 'FAILED'} — {msg.get('detail')}")
    elif kind == "places":
        say("  <- places:")
        for pl in msg.get("places", []):
            say(f"       {pl['id']:>4}  {pl.get('name') or '(unnamed)':<16} "
                  f"visits {pl['visits']:<5} anchors {pl['anchors']}")
    elif kind == "status":
        say("  <- status:\n" + json.dumps(msg, indent=6)[:2000])
    elif kind == "hello":
        say(f"  <- hello from node, schema {msg.get('schema')}, "
              f"commands: {', '.join(msg.get('commands', []))}")
    else:
        say(f"  <- {kind}: {json.dumps(msg)[:300]}")


# ------------------------------------------------------- websocket framing

def ws_frame(payload: bytes, opcode=0x1) -> bytes:
    head = bytes([0x80 | opcode])
    n = len(payload)
    if n < 126:
        head += bytes([n])
    elif n < 65536:
        head += bytes([126]) + struct.pack(">H", n)
    else:
        head += bytes([127]) + struct.pack(">Q", n)
    return head + payload


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("peer closed")
        buf += chunk
    return buf


def ws_read(sock):
    """Returns (opcode, payload). Handles fragmentation and masking."""
    opcode_out, data = None, b""
    while True:
        b1, b2 = recv_exact(sock, 2)
        fin = b1 & 0x80
        opcode = b1 & 0x0F
        masked = b2 & 0x80
        length = b2 & 0x7F

        if length == 126:
            length = struct.unpack(">H", recv_exact(sock, 2))[0]
        elif length == 127:
            length = struct.unpack(">Q", recv_exact(sock, 8))[0]

        mask = recv_exact(sock, 4) if masked else None
        payload = recv_exact(sock, length) if length else b""
        if mask:
            payload = bytes(c ^ mask[i % 4] for i, c in enumerate(payload))

        if opcode != 0x0:
            opcode_out = opcode
        data += payload
        if fin:
            return opcode_out, data


def handshake(sock, headers, key):
    accept = base64.b64encode(
        hashlib.sha1((key + GUID).encode()).digest()
    ).decode()
    sock.sendall(
        b"HTTP/1.1 101 Switching Protocols\r\n"
        b"Upgrade: websocket\r\n"
        b"Connection: Upgrade\r\n"
        b"Sec-WebSocket-Accept: " + accept.encode() + b"\r\n\r\n"
    )


# ------------------------------------------------------------- connections

def log_percept(p):
    if LOG:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps(p, ensure_ascii=False) + "\n")


def serve_ws(sock, addr, headers, key):
    handshake(sock, headers, key)
    node = f"{addr[0]}:{addr[1]}"
    with clients_lock:
        clients.append(sock)
    say(f"\n*** node connected from {node} — commands are live ***")
    try:
        while True:
            opcode, data = ws_read(sock)
            if opcode == 0x8:                       # close
                break
            if opcode == 0x9:                       # ping -> pong
                sock.sendall(ws_frame(data, 0xA))
                continue
            if opcode != 0x1:
                continue
            try:
                msg = json.loads(data.decode("utf-8", "replace"))
            except json.JSONDecodeError:
                continue

            kind = msg.get("type")
            if kind == "percept":
                deliver(msg, node)
            elif kind == "backlog":
                batch = msg.get("percepts", [])
                say(f"\n*** backlog flush: {len(batch)} percepts ***")
                for p in batch:
                    deliver(p, node)
            else:
                handle_reply(msg, node)
    except (ConnectionError, OSError) as e:
        say(f"\n*** node {node} disconnected ({e}) ***")
    finally:
        with clients_lock:
            if sock in clients:
                clients.remove(sock)
        sock.close()


def serve_http(sock, body_start, headers):
    """POST fallback, same as v0.1."""
    length = int(headers.get("content-length", 0))
    body = body_start
    while len(body) < length:
        body += sock.recv(min(65536, length - len(body)))

    if TOKEN and headers.get("authorization") != f"Bearer {TOKEN}":
        sock.sendall(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n")
        sock.close()
        return

    try:
        parsed = json.loads(body)
    except json.JSONDecodeError:
        sock.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
        sock.close()
        return

    batch = parsed if isinstance(parsed, list) else [parsed]
    for p in batch:
        deliver(p, "http")

    payload = json.dumps({"accepted": len(batch)}).encode()
    sock.sendall(
        b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
        + str(len(payload)).encode() + b"\r\n\r\n" + payload
    )
    sock.close()


def handle_conn(sock, addr):
    try:
        sock.settimeout(20)
        raw = b""
        while b"\r\n\r\n" not in raw:
            chunk = sock.recv(4096)
            if not chunk:
                sock.close()
                return
            raw += chunk
        head, _, rest = raw.partition(b"\r\n\r\n")
        lines = head.decode("latin-1").split("\r\n")
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()

        key = headers.get("sec-websocket-key")
        if key and "websocket" in headers.get("upgrade", "").lower():
            if TOKEN and headers.get("authorization") != f"Bearer {TOKEN}":
                sock.sendall(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n")
                sock.close()
                return
            sock.settimeout(None)
            serve_ws(sock, addr, headers, key)
        elif lines[0].startswith("POST"):
            serve_http(sock, rest, headers)
        else:
            body = b'{"ok":true,"hint":"ws:// for two-way, POST for one-way"}'
            sock.sendall(
                b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + str(len(body)).encode() + b"\r\n\r\n" + body
            )
            sock.close()
    except Exception as e:
        say(f"connection error: {e}")
        try:
            sock.close()
        except OSError:
            pass


# --------------------------------------------------------- command console

def send_cmd(obj):
    frame = ws_frame(json.dumps(obj).encode())
    with clients_lock:
        targets = list(clients)
    if not targets:
        say("  (no node connected)")
        return
    for c in targets:
        try:
            c.sendall(frame)
        except OSError:
            pass
    say(f"  -> {json.dumps(obj)}")


def parse_command(line):
    parts = line.split()
    if not parts:
        return None
    verb, rest = parts[0].lower(), parts[1:]

    if verb in ("sample", "read", "listen", "places", "status"):
        return {"cmd": verb}
    if verb == "look":
        return {"cmd": "look", "lens": rest[0] if rest else "back"}
    if verb == "speak":
        return {"cmd": "speak", "text": " ".join(rest)}
    if verb == "attend":
        if not rest:
            return {"cmd": "attend"}
        return {"cmd": "attend", "modality": rest[0],
                "gain": float(rest[1]) if len(rest) > 1 else 1.0}
    if verb == "name" and len(rest) >= 2:
        return {"cmd": "name_place", "id": rest[0], "name": " ".join(rest[1:])}
    if verb == "forget":
        return {"cmd": "forget", "what": rest[0] if rest else "habituation"}
    if verb == "set" and len(rest) >= 2:
        key, raw = rest[0], rest[1]
        if raw.lower() in ("true", "false"):
            val = raw.lower() == "true"
        else:
            try:
                val = int(raw)
            except ValueError:
                try:
                    val = float(raw)
                except ValueError:
                    val = raw
        return {"cmd": "set", key: val}
    say(f"  ? unrecognised: {line}")
    return None


def console():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        if line.lower() in ("quit", "exit"):
            os._exit(0)
        cmd = parse_command(line)
        if cmd:
            send_cmd(cmd)


def main():
    global TOKEN, LOG, STREAM, PROSE
    ap = argparse.ArgumentParser(
        description="Percepts leave on stdout as JSON lines; "
                    "anything for a person leaves on stderr.")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8077)
    ap.add_argument("--log", default="percepts.jsonl", help="'' to disable")
    ap.add_argument("--token", default="")
    ap.add_argument("--no-console", action="store_true")
    ap.add_argument("--jsonl", dest="jsonl", action="store_true", default=None,
                    help="percepts as JSON lines on stdout "
                         "(default: on when stdout is not a terminal)")
    ap.add_argument("--no-jsonl", dest="jsonl", action="store_false",
                    help="never write JSON lines to stdout")
    ap.add_argument("--quiet", action="store_true",
                    help="no per-percept human rendering")
    ap.add_argument("--pretty", action="store_true",
                    help="human rendering even while streaming JSONL")
    args = ap.parse_args()

    TOKEN = args.token or None
    LOG = args.log or None

    # Piped means something is consuming the data, so give it data. A terminal
    # means a person is watching, so give them the readable view. Either can be
    # forced, which is the bit that matters for scripts.
    STREAM = args.jsonl if args.jsonl is not None else not sys.stdout.isatty()
    PROSE = (not args.quiet) and (args.pretty or not STREAM)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(8)

    say(f"listening on ws://{args.host}:{args.port}/link  (and POST on the same port)")
    if LOG:
        say(f"logging to {LOG}")
    if STREAM:
        say("percepts are going to stdout as JSON lines")
    # A console needs someone at a keyboard. If stdin is a pipe there is nobody,
    # and reading it would swallow whatever is being piped in.
    if not args.no_console and sys.stdin.isatty():
        say("type commands below once a node connects — 'quit' to exit\n")
        threading.Thread(target=console, daemon=True).start()

    while True:
        sock, addr = srv.accept()
        threading.Thread(target=handle_conn, args=(sock, addr), daemon=True).start()


if __name__ == "__main__":
    main()
