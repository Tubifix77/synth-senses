#!/usr/bin/env python3
"""End-to-end test of the receiver's WebSocket path.

CI already proved the HTTP POST fallback works. The WebSocket is the primary
transport and the interesting one — it is what makes the phone an organ with
directable attention rather than a broadcaster — and nothing exercised it
automatically. The README's claim that the handshake, percept stream, backlog
flush and command replies all work came from a manual session that nobody can
re-run.

So: speak RFC 6455 at it as a real client would. Masked frames, a fragmented
message, a ping, a close handshake, and a check that what went in reached the
log. Stdlib only, no pip install, same as the receiver itself.

Exit code 0 means the wire protocol behaved.
"""
import base64
import hashlib
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
RECEIVER = os.path.join(HERE, "receiver.py")
# RFC 6455 section 1.3 gives both the magic GUID and a worked example, so the
# handshake can be checked against the spec itself instead of against a
# constant copied into this file. Getting that constant subtly wrong is exactly
# the mistake this test caught -- in the test, not in the receiver.
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
RFC_EXAMPLE_KEY = "dGhlIHNhbXBsZSBub25jZQ=="
RFC_EXAMPLE_ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="

failures = []


def check(label, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{'  ' + detail if detail else ''}")
    if not ok:
        failures.append(label)


# ----------------------------------------------------------------- framing
def mask_frame(payload: bytes, opcode=0x1, fin=True) -> bytes:
    """A client MUST mask. The receiver has to unmask correctly or nothing works."""
    head = bytes([(0x80 if fin else 0x00) | opcode])
    n = len(payload)
    if n < 126:
        head += bytes([0x80 | n])
    elif n < 65536:
        head += bytes([0x80 | 126]) + struct.pack(">H", n)
    else:
        head += bytes([0x80 | 127]) + struct.pack(">Q", n)
    key = os.urandom(4)
    masked = bytes(c ^ key[i % 4] for i, c in enumerate(payload))
    return head + key + masked


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("receiver closed the connection")
        buf += chunk
    return buf


def read_frame(sock):
    """(opcode, payload). Server frames are unmasked."""
    b1, b2 = recv_exact(sock, 2)
    opcode = b1 & 0x0F
    masked = b2 & 0x80
    length = b2 & 0x7F
    if length == 126:
        length = struct.unpack(">H", recv_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack(">Q", recv_exact(sock, 8))[0]
    key = recv_exact(sock, 4) if masked else None
    payload = recv_exact(sock, length) if length else b""
    if key:
        payload = bytes(c ^ key[i % 4] for i, c in enumerate(payload))
    return opcode, payload


def percept(seq, narration="CI percept."):
    return {
        "type": "percept", "schema": 2, "device_id": "ci", "seq": seq,
        "ts": "2026-01-01T00:00:00.000Z", "trigger": "salient",
        "attention": {"salience": 0.9, "novel": ["v:Room"], "known_tokens": 3,
                      "dishabituated": False, "floor_reason": None},
        "vision": {"lens": "back", "brightness": 0.4,
                   "labels": [{"name": "Room", "conf": 0.9}], "objects": [], "text": None},
        "hearing": None, "radio": None,
        "body": {"motion": "still", "posture": "face_up", "accel_rms": 0.0, "gyro_rms": 0.0,
                 "heading_deg": None, "steps": None, "steps_delta": None, "lux": None,
                 "covered": None, "magnetic_ut": None, "magnetic_anomaly": None,
                 "pressure_hpa": None, "pressure_delta_per_min": None},
        "self": {"battery": 1.0, "charging": True, "battery_temp_c": None, "current_ma": None,
                 "voltage_v": None, "thermal": "NONE", "thermal_headroom": None,
                 "mem_free_pct": 0.5, "mem_low": False, "storage_free_pct": None,
                 "screen_on": False, "net": "wifi", "uptime_s": 1},
        "tempo": {"local_time": "00:00", "tz_offset_min": 0, "day_of_week": "Thursday",
                  "part_of_day": "deep night", "solar_elevation_deg": None,
                  "is_daylight": None, "minutes_to_sunset": None,
                  "minutes_since_sunrise": None, "day_length_min": None},
        "gps": None, "narration": narration,
    }


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def wait_for_port(port, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return True
        except OSError:
            time.sleep(0.2)
    return False


def main():
    port = free_port()
    logdir = tempfile.mkdtemp()
    log = os.path.join(logdir, "percepts.jsonl")

    proc = subprocess.Popen(
        [sys.executable, RECEIVER, "--host", "127.0.0.1", "--port", str(port),
         "--log", log, "--no-console"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    try:
        if not wait_for_port(port):
            print("receiver never opened its port")
            print(proc.stdout.read() if proc.stdout else "")
            return 1

        print("websocket transport")
        sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        sock.settimeout(10)

        # ---- RFC 6455 handshake, using the spec's own worked example ----
        key = RFC_EXAMPLE_KEY
        sock.sendall(
            f"GET /link HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
            f"Upgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode()
        )
        resp = b""
        while b"\r\n\r\n" not in resp:
            resp += sock.recv(4096)
        head = resp.split(b"\r\n\r\n")[0].decode()

        check("101 Switching Protocols", head.startswith("HTTP/1.1 101"))
        check("Upgrade: websocket", "upgrade: websocket" in head.lower())
        # Case-sensitive on the value: base64 is, and a client that lowercases
        # it would be rejected by any conforming server.
        accept = next(
            (l.split(":", 1)[1].strip() for l in head.split("\r\n")
             if l.lower().startswith("sec-websocket-accept:")), None
        )
        check("Sec-WebSocket-Accept matches RFC 6455's worked example",
              accept == RFC_EXAMPLE_ACCEPT, f"got {accept!r}")
        # and the same value derived independently, as a second opinion
        check("and equals an independent derivation",
              accept == base64.b64encode(
                  hashlib.sha1((key + GUID).encode()).digest()).decode())

        # ---- the phone announces itself ----
        sock.sendall(mask_frame(json.dumps(
            {"type": "hello", "schema": 2, "commands": ["sample", "look", "status"]}
        ).encode()))

        # ---- a percept ----
        sock.sendall(mask_frame(json.dumps(percept(1)).encode()))

        # ---- a backlog flush ----
        sock.sendall(mask_frame(json.dumps(
            {"type": "backlog", "percepts": [percept(2), percept(3)]}
        ).encode()))

        # ---- a reply to a command ----
        sock.sendall(mask_frame(json.dumps(
            {"type": "ack", "cmd": "sample", "ok": True, "detail": "sampled"}
        ).encode()))

        # ---- a fragmented percept, which a real client may well send ----
        big = json.dumps(percept(4, "A" * 400)).encode()
        cut = len(big) // 2
        sock.sendall(mask_frame(big[:cut], opcode=0x1, fin=False))
        sock.sendall(mask_frame(big[cut:], opcode=0x0, fin=True))

        # ---- ping must be answered with a matching pong ----
        sock.sendall(mask_frame(b"keepalive", opcode=0x9))
        opcode, payload = read_frame(sock)
        check("ping is answered with pong", opcode == 0xA, f"opcode={opcode:#x}")
        check("pong echoes the ping payload", payload == b"keepalive", repr(payload))

        # ---- clean close ----
        sock.sendall(mask_frame(struct.pack(">H", 1000), opcode=0x8))
        time.sleep(0.6)
        sock.close()
        time.sleep(0.6)

        # ---- what reached the log ----
        lines = []
        if os.path.exists(log):
            with open(log, encoding="utf-8") as f:
                lines = [json.loads(l) for l in f if l.strip()]
        seqs = [p.get("seq") for p in lines]

        check("the single percept was logged", 1 in seqs, f"seqs={seqs}")
        check("both backlog percepts were logged", 2 in seqs and 3 in seqs, f"seqs={seqs}")
        check("the fragmented percept was reassembled and logged", 4 in seqs, f"seqs={seqs}")
        check("exactly four percepts, so nothing was double-counted", len(lines) == 4,
              f"{len(lines)} lines")
        if 4 in seqs:
            frag = next(p for p in lines if p.get("seq") == 4)
            check("the reassembled payload is intact", frag["narration"] == "A" * 400)
        check("a non-percept reply is not logged as a percept",
              all(p.get("type") != "ack" for p in lines))

        print(f"\n{len(failures)} failure(s)")
        for f in failures:
            print("  -", f)
        return 1 if failures else 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    sys.exit(main())
