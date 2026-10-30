#!/usr/bin/env python3
"""Let the Potluck cell steer the phone: numbers in an inbox become commands.

The other direction from potluck_bridge.py. The bridge publishes what the phone
senses into the `phone` inbox; this reads a second inbox, `phone_steer`, that
the desk, a board's actor or Home Assistant may write, and turns each change
into the command the phone already understands (docs/PROTOCOL.md). A Potluck
value is a bool or a number of at most eight bytes, so a command is a number
with a meaning, the same way the bridge's sound classes are.

Scope, as Tue chose it on 2026-10-31: nudges and sense switches. A sample on
demand, the lens, the interval, the threshold, and vision, hearing, speech and
location on or off. Not `speak`, not `forget`, not naming places. Anything
outside-writable can also be written by whoever can publish on the cell's MQTT
broker, which he knew when he chose it.

How it behaves, each because the alternative goes wrong:

- **Only a change is a command.** The first look at the hub is a baseline, so
  a restart never replays a switch someone flipped last week.
- **`sample` is a counter.** Writing a value it does not already hold takes a
  sample, the way a speak actor's `say` works. Writing the same number twice
  is one sample, because nothing changed.
- **The switches show the truth.** After every `set`, and on every connect,
  the phone's own `status` is written back into the inbox, so a switch flipped
  from the PC console or the phone's panel is not left showing the old state.
- **Out of range is clamped, not refused**, to what `set` accepts.

    python3 tools/potluck_steer.py --manifest      # the inbox, for the cell's owner

It runs inside synth_senses_service.py, which has the receiver to send through.
"""
import json
import sys
import threading

INBOX = "phone_steer"

#: (name, kind, unit). Order is the manifest's; values may be appended, never
#: inserted or renamed, because the Potluck Desk builds its controls from them.
VALUES = [
    ("sample",     "u32",  None),
    ("lens",       "u32",  None),
    ("interval_s", "u32",  None),
    ("threshold",  "f32",  None),
    ("vision",     "bool", None),
    ("hearing",    "bool", None),
    ("speech",     "bool", None),
    ("location",   "bool", None),
]

#: `lens` as an ordinal. Append-only, like every vocabulary that crosses.
LENSES = ["back", "front"]

SWITCHES = ("vision", "hearing", "speech", "location")

#: What `set` accepts (docs/PROTOCOL.md).
INTERVAL_S = (1, 600)

PREFIX = "act/%s/" % INBOX


def steer_values(model):
    """{name: value} for every value of the steering inbox that holds one."""
    out = {}
    for r in (model or {}).get("resources") or []:
        name = r.get("name") or ""
        if name.startswith(PREFIX) and r.get("value") is not None:
            out[name[len(PREFIX):]] = r["value"]
    return out


def _number(v):
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return None
    return v


def _switch(v):
    if isinstance(v, bool):
        return v
    n = _number(v)
    return None if n is None else n != 0


class Steer:
    """The translation, with no sockets: values in, commands and write-backs out."""

    def __init__(self):
        self.seen = None              # what the hub held at the last look
        self._back = {}               # write-backs waiting to go
        self._lock = threading.Lock()

    def commands(self, values):
        """-> the commands this look at the hub calls for, `set` first."""
        with self._lock:
            if self.seen is None:
                self.seen = dict(values)
                return []
            changed = {k: v for k, v in values.items() if self.seen.get(k) != v}
            self.seen.update(values)
            # A change the phone already reports is one this wrote back.
            for k in list(changed):
                if self._back.get(k) == changed[k]:
                    del self._back[k]
        settings = {}
        if "interval_s" in changed:
            n = _number(changed["interval_s"])
            if n is not None:
                settings["interval_ms"] = int(min(max(round(n), INTERVAL_S[0]), INTERVAL_S[1])) * 1000
        if "threshold" in changed:
            n = _number(changed["threshold"])
            if n is not None:
                settings["threshold"] = round(min(max(float(n), 0.0), 1.0), 3)
        if "lens" in changed:
            n = _number(changed["lens"])
            if n is not None and float(n).is_integer() and 0 <= int(n) < len(LENSES):
                settings["lens"] = LENSES[int(n)]
        for k in SWITCHES:
            if k in changed:
                b = _switch(changed[k])
                if b is not None:
                    settings[k] = b
        out = []
        if settings:
            out.append(dict({"cmd": "set"}, **settings))
        if "sample" in changed and _number(changed["sample"]) is not None:
            out.append({"cmd": "sample"})
        return out

    def phone_said(self, status):
        """A status reply: queue whatever it says that the hub does not hold."""
        if not isinstance(status, dict):
            return
        senses = status.get("senses") or {}
        now = {}
        ms = _number(status.get("interval_ms"))
        if ms is not None:
            now["interval_s"] = int(round(ms / 1000.0))
        th = _number(status.get("threshold"))
        if th is not None:
            now["threshold"] = round(float(th), 3)
        if senses.get("lens") in LENSES:
            now["lens"] = LENSES.index(senses["lens"])
        for k in SWITCHES:
            if isinstance(senses.get(k), bool):
                now[k] = senses[k]
        with self._lock:
            seen = self.seen or {}
            for k, v in now.items():
                if seen.get(k) != v:
                    self._back[k] = v

    def write_backs(self):
        """-> [(name, value)] still to write, without taking them."""
        with self._lock:
            return list(self._back.items())

    def wrote(self, name, value):
        """The hub took it. Only now is it what the hub holds -- marking it
        sooner would read a failed write's old value as a fresh command."""
        with self._lock:
            if self._back.get(name) == value:
                del self._back[name]
            if self.seen is not None:
                self.seen[name] = value


def steer_forever(get_model, write, send, steer, stop, say, every_s=2.0):
    """Look at the hub every `every_s` until `stop`.

    `get_model()` -> the model or None; `write(name, value)` -> (ok, note);
    `send(command)` passes one command to the phone. A `set` is followed by a
    `status`, whose answer comes back through `steer.phone_said`.
    """
    up = None
    while not stop.is_set():
        try:
            model = get_model()
        except Exception:
            model = None
        if (model is not None) != up:
            up = model is not None
            say("steering: hub %s" % ("answering" if up else "not answering"))
        if model is not None:
            for cmd in steer.commands(steer_values(model)):
                say("steering: the cell asks %s" % json.dumps(cmd))
                send(cmd)
                if cmd["cmd"] == "set":
                    send({"cmd": "status"})
            for name, value in steer.write_backs():
                ok, note = write(name, value)
                if ok:
                    steer.wrote(name, value)
                else:
                    break             # the budget is shared with the bridge
        stop.wait(every_s)


def manifest_outputs():
    return " ".join("%s:%s:%s" % (n, k, u) if u else "%s:%s" % (n, k) for n, k, u in VALUES)


def main():
    if "--manifest" in sys.argv[1:]:
        print(json.dumps({"name": INBOX, "module": "builtin:inbox", "latency_class": 4, "pin": "A",
                          "config": {"outputs": manifest_outputs(), "stale_ms": 86400000}}, indent=2))
        print("\nand in the cluster file's \"outside\", for each value:", file=sys.stderr)
        for n, _k, _u in VALUES:
            print('  "act/%s/%s": {"mode": "read_write", "writers": ["Potluck Desk", "Synth Senses"]}'
                  % (INBOX, n), file=sys.stderr)
        return 0
    print(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(main())
