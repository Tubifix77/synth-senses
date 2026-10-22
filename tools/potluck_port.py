#!/usr/bin/env python3
"""The phone's senses, as Potluck holds them, served to a companion on this PC.

    python3 tools/potluck_port.py --hub https://192.168.0.7:8443

A mind does not read this phone directly. Tue's direction is that it reads the
senses through Potluck, so that one hub gives it the whole house. This is the
small program between the two: it reads the Potluck cell with the pairing the
phone's bridge already holds, and serves the five readings the companion in
D:\\Projects\\ai-consciousness polls, as plain JSON on GET. That companion's
contract is read-only, a JSON object per path, at most 64 KB, a 3 s timeout, no
redirects, and staleness reported by whoever serves the reading.

What it answers, agreed with that project:

    200  a JSON object of the readings present, always with `age_s` -- the age of
         the oldest reading in it, as the hub measured it, plus the time since
         this port last read the hub
    503  nothing sensed on that path right now. An honest absence, which the
         companion records as such -- not blindness.
    502  the port cannot reach the hub. That is real trouble, and the companion
         counts it as a blind spot.
    404  not a sense this port serves
    405  anything but GET. The companion gains senses, never actuators.

A reading that is absent, STALE, NO_DATA, out of range or without an age is left
out of its object, never sent as zero. STALE is withheld because the bridge
refreshes only what the phone still reports, so a STALE value is one the phone
has stopped reporting -- a sense switched off answers 503 within minutes, rather
than its last reading being presented as now. The phone side spent a day removing fake zeros,
and the companion's own accounting depends on gaps staying visible.

**There is no location path, and no way to turn one on.** Coordinates entering
a companion's signed memory would be permanent, so it is not built: "not built"
is the real limit, a switched-off capability is still a built one. `place` -- an
opaque room-level id -- carries "which room am I in" instead. If Tue later wants
coordinates, building it is that decision.

Loopback only, by construction rather than by a flag: the companion runs on this
PC, so nothing here touches the network except the read from the hub.
"""
import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import potluck_bridge as pb  # noqa: E402  the pinned hub client, the vocabulary

#: Loopback, and deliberately not a flag. See the module docstring.
BIND = "127.0.0.1"
PORT = 8079

#: How long since the last good read of the hub before the port says the link
#: is down. Several poll intervals, so one slow read is not a blind spot.
LINK_DOWN_S = 30.0

#: Qualities worth passing on: GOOD only. STALE was served too at first, with
#: `age_s` saying how stale, but once the bridge refreshes only values the phone
#: is still reporting, STALE means exactly "the phone stopped saying this" --
#: and a sense that has stopped is an absence, not an old present. Serving it
#: as a 200 kept it in the companion's current state.
SERVED_QUALITIES = ("GOOD",)

#: path -> ((field in the answer, Potluck value under act/phone/), ...)
#: These five and no others. Note what is not here: lat and lon.
ROUTES = {
    "/phone/body": (("motion", "motion"), ("posture", "posture"), ("steps", "steps")),
    "/phone/hearing": (("sound", "sound"), ("loudness_db", "loudness")),
    "/phone/place": (("place", "place"), ("ble_count", "ble_count")),
    "/phone/self": (("battery", "battery"), ("battery_temp_c", "battery_temp")),
    "/phone/attention": (("salience", "salience"),),
}

PREFIX = "act/phone/"


def phone_values(model):
    """The phone's resources from a hub model, by value name."""
    out = {}
    for r in model.get("resources") or []:
        name = r.get("name") or ""
        if name.startswith(PREFIX):
            out[name[len(PREFIX):]] = r
    return out


def _reading(value_name, resource):
    """-> (value, age_ms) worth serving, or None to leave the field out."""
    if not resource or resource.get("quality") not in SERVED_QUALITIES:
        return None
    v = resource.get("value")
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return None
    age = resource.get("age_ms")
    # A reading whose age cannot be stated is one this port cannot vouch for.
    if isinstance(age, bool) or not isinstance(age, (int, float)) or age < 0:
        return None
    words = pb.VOCABULARIES.get(value_name)
    if words is not None:
        # An ordinal: turned back into its word, or left out if it names none.
        if not float(v).is_integer() or not 0 <= int(v) < len(words):
            return None
        v = words[int(v)]
    elif isinstance(v, float):
        # Potluck holds 32-bit floats, so -47.8 arrives as -47.799999: noise in
        # the sixth decimal, a precision nobody measured. It would sit in a
        # companion's signed memory for good, so it is trimmed here.
        v = round(v, 3)
    return v, age


def render(path, values, elapsed_s):
    """-> (status, body) for one path, from the phone's values at the last read."""
    fields = ROUTES.get(path)
    if fields is None:
        return 404, {"error": "not a sense this port serves"}
    body, oldest_s = {}, None
    for out_name, value_name in fields:
        got = _reading(value_name, values.get(value_name))
        if got is None:
            continue
        value, age_ms = got
        body[out_name] = value
        age_s = age_ms / 1000.0 + elapsed_s
        oldest_s = age_s if oldest_s is None else max(oldest_s, age_s)
    if not body:
        return 503, {"error": "nothing sensed here right now"}
    body["age_s"] = int(round(oldest_s))
    return 200, body


class Snapshot:
    """The last good read of the hub, and when it was taken."""

    def __init__(self, now=time.monotonic):
        self.now = now
        self.lock = threading.Lock()
        self.values = None
        self.at = None

    def update(self, model):
        values = phone_values(model)
        with self.lock:
            self.values, self.at = values, self.now()

    def answer(self, path):
        if path not in ROUTES:
            return 404, {"error": "not a sense this port serves"}
        with self.lock:
            values, at = self.values, self.at
        if at is None or self.now() - at > LINK_DOWN_S:
            return 502, {"error": "the Potluck hub is not answering"}
        return render(path, values, self.now() - at)


def make_handler(snapshot):
    class Handler(BaseHTTPRequestHandler):
        server_version = "synth-senses-port"

        def _send(self, status, body):
            data = json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            self._send(*snapshot.answer(self.path.split("?", 1)[0]))

        def _refuse(self):
            self._send(405, {"error": "read-only: the companion gains senses, never actuators"})

        do_POST = do_PUT = do_DELETE = do_PATCH = _refuse

        def log_message(self, fmt, *args):
            pass

    return Handler


def poll_forever(hub, snapshot, every_s, say, stop=None):
    """Read the hub's model every few seconds; say so only when the link changes."""
    was_up = None
    while not (stop and stop.is_set()):
        try:
            status, body, _headers = hub.get("/api/model")
            up = status == 200 and isinstance(body, dict)
            if up:
                snapshot.update(body)
        except Exception:  # network trouble is the 502 case, not a crash
            up = False
        if up != was_up:
            say("hub %s" % ("answering" if up else "NOT answering -- paths now give 502"))
            was_up = up
        time.sleep(every_s)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--hub", required=True, help="https://<host>:8443 of the Potluck hub")
    ap.add_argument("--token-file", default=".potluck-token.json",
                    help="the pairing the phone's bridge already holds")
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--every", type=float, default=5.0, help="seconds between reads of the hub")
    args = ap.parse_args()

    def say(msg):
        print(msg, file=sys.stderr, flush=True)

    try:
        with open(args.token_file, encoding="utf-8") as f:
            stored = json.load(f)
    except (OSError, ValueError) as e:
        say("no pairing in %s (%s): pair once with potluck_bridge.py --pair" % (args.token_file, e))
        return 2
    if not stored.get("token"):
        say("no token in %s: pair once with potluck_bridge.py --pair" % args.token_file)
        return 2

    hub = pb.Hub(args.hub, token=stored["token"], fingerprint=stored.get("fingerprint"))
    snapshot = Snapshot()
    threading.Thread(target=poll_forever, args=(hub, snapshot, args.every, say),
                     daemon=True).start()
    server = ThreadingHTTPServer((BIND, args.port), make_handler(snapshot))
    say("serving %s on http://%s:%d -- loopback only" % (", ".join(ROUTES), BIND, args.port))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
