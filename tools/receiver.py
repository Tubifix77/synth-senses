#!/usr/bin/env python3
"""
Two-way percept receiver for Synth Senses v0.2 — stdlib only, no pip install.

    python3 receiver.py                      # ws + http on :8077
    python3 receiver.py --port 9000 --token s3cret

Then press Find receiver in the app: this broadcasts where it is on UDP 8078
every couple of seconds, and the app fills its Endpoint field in from that.
Failing that, set the endpoint to  ws://<this-machine-ip>:8077/link
by hand, or pass --no-beacon if you would rather it kept quiet.

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
import time
from datetime import datetime

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
BEACON_PORT = 8078      # UDP. Discovery only; no percept ever travels on it.
BEACON_EVERY_S = 2.0
BEACON_MAGIC = "synth_senses"
BEACON_VERSION = 1
TOKEN = None
LOG = None
STREAM = False          # percepts as JSON lines on stdout
REPLIES = False         # the phone's answers as JSON lines on stdout too
PROSE = True            # the human rendering, on stderr
clients = []
clients_lock = threading.Lock()
out_lock = threading.Lock()


def lan_addresses():
    """Addresses a phone on the same network could actually dial.

    0.0.0.0 is a bind address, not a destination, so printing it helps nobody.
    The UDP trick sends no packet; it just asks the routing table which local
    interface would carry traffic off this machine.
    """
    found = []
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            probe.connect(("192.0.2.1", 9))          # TEST-NET-1, never routed
            found.append(probe.getsockname()[0])
        finally:
            probe.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(info[4][0])
    except OSError:
        pass

    out = []
    for a in found:
        if a.startswith(("127.", "169.254.")) or a in out:
            continue
        out.append(a)
    return out


def beacon_payload(port, addrs=None):
    """The announcement, as bytes.

    Small on purpose: a magic key with a version, the address to dial, and the
    hostname so a person watching `tcpdump` can tell whose laptop it is. The
    phone puts the `ws` value back through its own URL rules before offering
    it to anyone, so this is a suggestion rather than something trusted.
    """
    if addrs is None:
        addrs = lan_addresses()
    msg = {BEACON_MAGIC: BEACON_VERSION, "port": port}
    # The first address came from the routing table, so it is the one a phone
    # on the same network would actually reach.
    if addrs:
        msg["ws"] = f"ws://{addrs[0]}:{port}/link"
    try:
        msg["host"] = socket.gethostname()
    except OSError:
        pass
    return json.dumps(msg).encode("utf-8")


def broadcast_targets(addrs):
    """Where to send it.

    255.255.255.255 is the limited broadcast and never leaves the link, which
    is what we want -- but Android's WiFi driver is more reliable about
    delivering a subnet-directed broadcast, so send both. The /24 is an
    assumption: it is right on a home network, and on anything else the
    limited broadcast still carries, so a wrong guess here costs nothing.
    """
    targets = ["255.255.255.255"]
    for a in addrs:
        parts = a.split(".")
        if len(parts) == 4:
            directed = ".".join(parts[:3]) + ".255"
            if directed not in targets:
                targets.append(directed)
    return targets


def beacon(port, beacon_port=BEACON_PORT):
    """Say where we are, every couple of seconds, forever.

    This is the other half of the app's Find button. Typing
    `ws://192.168.0.7:8077/link` on a phone keyboard, from an example on a
    different subnet, is the worst part of setting this up.

    Never touches stdout: the contract is that stdout carries percepts and
    replies and nothing else.
    """
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    except OSError as e:
        say(f"no beacon: {e}")
        return

    addrs, refreshed = lan_addresses(), time.monotonic()
    while True:
        # Cheap, but it can hit a name server on a badly configured machine,
        # so it is not something to do every two seconds.
        if time.monotonic() - refreshed > 30:
            addrs, refreshed = lan_addresses(), time.monotonic()
        payload = beacon_payload(port, addrs)
        for target in broadcast_targets(addrs):
            try:
                sock.sendto(payload, (target, beacon_port))
            except OSError:
                pass        # a down interface, a firewall: try again in 2 s
        time.sleep(BEACON_EVERY_S)


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


def emit_reply(msg):
    """Put one of the phone's answers on stdout, for whatever sent the command.

    Percepts and replies share the stream and are told apart by a single rule,
    which [INTEGRATION.md] states and `test_receiver_ws.py` pins: **a reply
    always carries "type"; a percept never does.** `deliver` strips the
    envelope from every percept precisely so that stays true no matter which
    transport brought it.

    Off unless something is actually driving the receiver, so the plain
    `receiver | consumer` pipeline keeps the exact stream it has always had.
    """
    if not REPLIES:
        return
    line = json.dumps(msg, ensure_ascii=False)
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
    # To the machine first, and outside the renderer's reach: a program that
    # issued a command is owed its answer even if the pretty-printer below
    # trips over an unfamiliar shape. Same reasoning as `deliver`.
    emit_reply(msg)
    if not PROSE:
        return
    kind = msg.get("type")
    if kind == "ack":
        say(f"  <- ack {msg.get('cmd')}: "
              f"{'ok' if msg.get('ok') else 'FAILED'} — {msg.get('detail')}")
    elif kind == "places":
        say("  <- places:")
        total = len(msg.get("places", []))
        degenerate = 0
        for pl in msg.get("places", []):
            # stable_anchors is the number passing the persistence filter, so
            # the ones that actually contribute to recognition. The app has
            # always sent it; printing only `anchors` hid exactly the number
            # that explains a place recognising unreliably -- or a place with
            # one anchor scoring a perfect match on seeing that one anchor.
            stable = pl.get("stable_anchors")
            mark = ""
            if isinstance(stable, int) and stable == 0:
                degenerate += 1
                # A place seen once has stable 0 by construction: MIN_SEEN is
                # 2, so after one visit every anchor is at seen=1 however many
                # there are -- p38 had 20 anchors and 0 stable purely because
                # it was new. `isLearning()` exists so such a place can still
                # be recognised while it matures. So this is only a complaint
                # about places that had their chances and never took them.
                mark = ("   <-- new, not matured yet" if pl.get("visits", 0) <= 1
                        else "   <-- visited repeatedly and still nothing "
                             "stable: it will recognise unreliably")
            say(f"       {pl['id']:>4}  {pl.get('name') or '(unnamed)':<16} "
                f"visits {pl['visits']:<5} anchors {pl['anchors']:<5} "
                f"stable {stable if stable is not None else '?'}{mark}")
        if total:
            stuck = sum(1 for pl in msg.get("places", [])
                        if pl.get("stable_anchors") == 0 and pl.get("visits", 0) > 1)
            say(f"       -- {total} places, {degenerate} with no stable "
                f"anchors ({stuck} of them visited more than once)")
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
        # A person reads this and shrugs; a program has to be told, or it waits
        # forever for an answer to a command that never left the building.
        say("  (no node connected)")
        emit_reply({"type": "error", "error": "no node connected",
                    "cmd": obj.get("cmd")})
        return
    sent = 0
    for c in targets:
        try:
            c.sendall(frame)
            sent += 1
        except OSError:
            pass
    if sent == 0:
        say("  (every connection failed)")
        emit_reply({"type": "error", "error": "send failed",
                    "cmd": obj.get("cmd")})
        return
    say(f"  -> {json.dumps(obj)}")


# The eleven commands the phone accepts, from PROTOCOL.md. Kept here so a
# JSON line naming something else is refused with a reason rather than
# forwarded and silently ignored by the phone.
KNOWN_COMMANDS = frozenset((
    "sample", "look", "read", "listen", "speak", "attend",
    "set", "places", "name_place", "forget", "status",
))


def command_from_json(line):
    """One JSON line to a command, or an error object saying why not.

    Returns (command, error). Exactly one is not None.
    """
    try:
        obj = json.loads(line)
    except ValueError as e:
        return None, {"type": "error", "error": "not valid JSON",
                      "detail": str(e), "line": line[:200]}
    if not isinstance(obj, dict):
        hint = (" - send one object per line rather than a batch"
                if isinstance(obj, list) else "")
        return None, {"type": "error", "error": "not a JSON object",
                      "detail": "a command is an object, not %s%s"
                                % (type(obj).__name__, hint),
                      "line": line[:200]}
    cmd = obj.get("cmd")
    if not isinstance(cmd, str):
        return None, {"type": "error", "error": "no cmd field",
                      "detail": "every command needs a string 'cmd'",
                      "line": line[:200]}
    if cmd not in KNOWN_COMMANDS:
        return None, {"type": "error", "error": "unknown command",
                      "detail": "%r is not one of: %s"
                                % (cmd, ", ".join(sorted(KNOWN_COMMANDS))),
                      "cmd": cmd}
    return obj, None


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


def commands_from_stdin():
    """Commands in as JSON lines, the mirror image of percepts out.

    This is the half that made the phone unsteerable from code. Until it
    existed the command vocabulary could only be typed at a console, which
    needs a terminal on stdin, so a downstream program could watch but never
    look. Directable attention is most of what makes this an organ rather than
    a webcam, and half of it was unreachable.

    One JSON object per line, exactly as PROTOCOL.md defines it:

        {"cmd": "look", "lens": "front"}
        {"cmd": "attend", "modality": "s", "gain": 2.0}

    A line that is not JSON is tried as the console's own shorthand, so
    `echo status | receiver.py` does what it obviously should. Nothing here
    ever writes to stdout except through `emit_reply`; diagnostics go to
    stderr, because stdout is the percept stream and belongs to the consumer.

    EOF does not stop the receiver. The phone is still connected and its
    percepts are still worth logging, so the controller going away ends the
    steering, not the sensing.
    """
    for raw in sys.stdin:
        line = raw.strip()
        if not line:
            continue
        if line.startswith(("{", "[")):
            cmd, err = command_from_json(line)
            if err is not None:
                say("  ? %s: %s" % (err["error"], err.get("detail", "")))
                emit_reply(err)
                continue
        else:
            # the console shorthand, for a human at the other end of a pipe
            cmd = parse_command(line)
            if cmd is None:
                emit_reply({"type": "error", "error": "unrecognised",
                            "line": line[:200]})
                continue
        send_cmd(cmd)
    say("  (end of command input; still listening to the phone)")


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


def utf8_streams():
    """Make stdout and stderr UTF-8, whatever the platform thinks.

    A percept's narration, an OCR line or a transcript can hold any character
    at all. On Windows a *pipe* gets the locale encoding rather than UTF-8 --
    usually cp1252 -- so the first non-Latin-1 character raises
    UnicodeEncodeError in the middle of the stream, and the percept feed dies
    on a received word rather than on anything this code did. Found while
    reading a test's own output on the machine this runs on.

    Python 3.7+ only, and `reconfigure` is absent on a replaced stream, so a
    failure here is not worth dying over.
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):
            pass


def main():
    global TOKEN, LOG, STREAM, PROSE, REPLIES
    utf8_streams()
    ap = argparse.ArgumentParser(
        description="Percepts leave on stdout as JSON lines; "
                    "anything for a person leaves on stderr.")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8077)
    ap.add_argument("--log", default="percepts.jsonl", help="'' to disable")
    ap.add_argument("--token", default="")
    ap.add_argument("--no-console", action="store_true",
                    help="read nothing from stdin: no console, no commands")
    ap.add_argument("--replies", dest="replies", action="store_true",
                    default=None,
                    help="the phone's answers as JSON lines on stdout "
                         "(default: on when commands are being read from a "
                         "pipe). A reply has \"type\"; a percept never does.")
    ap.add_argument("--no-replies", dest="replies", action="store_false",
                    help="never write replies to stdout")
    ap.add_argument("--jsonl", dest="jsonl", action="store_true", default=None,
                    help="percepts as JSON lines on stdout "
                         "(default: on when stdout is not a terminal)")
    ap.add_argument("--no-jsonl", dest="jsonl", action="store_false",
                    help="never write JSON lines to stdout")
    ap.add_argument("--no-beacon", dest="beacon", action="store_false",
                    help="do not broadcast this machine's address on the LAN")
    ap.add_argument("--beacon-port", type=int, default=BEACON_PORT,
                    help=f"UDP port to announce on (default {BEACON_PORT})")
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

    # A terminal on stdin means a person typing words. A pipe means a program
    # sending JSON. Either way stdin is the control channel -- it was simply
    # unreachable from a program until now.
    driving = (not args.no_console) and (not sys.stdin.isatty())
    REPLIES = args.replies if args.replies is not None else driving

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(8)

    if args.beacon:
        threading.Thread(
            target=beacon, args=(args.port, args.beacon_port), daemon=True
        ).start()

    if args.host in ("0.0.0.0", "::"):
        addrs = lan_addresses()
        if addrs:
            say("set the phone's endpoint to:")
            for i, a in enumerate(addrs):
                # the first came from the routing table, the rest are usually
                # virtual adapters for Docker, WSL, Hyper-V or a VPN
                note = "   <-- try this one" if i == 0 and len(addrs) > 1 else ""
                say(f"    ws://{a}:{args.port}/link{note}")
            if args.beacon:
                say("    ...or press Find receiver in the app, which listens "
                    f"for the beacon on UDP {args.beacon_port}")
        else:
            say("could not work out this machine's LAN address; find it with "
                "`ipconfig` on Windows or `ip addr` elsewhere")
        say(f"(bound to every interface on port {args.port}; POST works on the same port)")
        say("if the phone cannot connect, the host firewall is the usual reason: "
            f"inbound TCP {args.port} has to be allowed on the private network")
    else:
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
    elif driving:
        say("reading commands from stdin as JSON lines"
            + ("; replies go to stdout" if REPLIES else ""))
        threading.Thread(target=commands_from_stdin, daemon=True).start()

    # A bare blocking accept() swallows Ctrl+C on Windows: the main thread sits
    # in a C-level call that never yields, so SIGINT is not delivered until a
    # connection happens to arrive. Observed the first evening this was run for
    # real -- the only way out was the console's own `quit`, which does not
    # exist when stdin is a pipe. A short timeout gives the interpreter a
    # chance to notice.
    srv.settimeout(0.5)
    try:
        while True:
            try:
                sock, addr = srv.accept()
            except socket.timeout:
                continue
            threading.Thread(target=handle_conn, args=(sock, addr),
                             daemon=True).start()
    except KeyboardInterrupt:
        say("")
        say("stopping.")
        return 0


if __name__ == "__main__":
    main()
