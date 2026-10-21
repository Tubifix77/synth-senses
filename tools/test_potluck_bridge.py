#!/usr/bin/env python3
"""The Potluck bridge: what crosses, what does not, and how fast.

Stdlib only, no hub, no sockets. The bridge takes its clock and its writer as
arguments precisely so this is possible -- the same trick that keeps
Habituation and PlaceMemory off Robolectric on the other side of the project.

Three things here are worth more than the rest:

  * the ordinals are checked against Vocabulary.kt rather than restated, so
    the two cannot drift. A restated copy is exactly how MainActivityLaunchTest
    came to grant the wrong permissions for three API levels;
  * absence stays absence. A phone with no radio must publish nothing for
    `place`, not zero, or a board reads a confident lie;
  * a backlog is never replayed. Potluck holds a current value and its age,
    so flushing two thousand spooled percepts must produce ten writes, not
    twenty thousand.

Run: python3 tools/test_potluck_bridge.py
"""
import io
import itertools
import json
import os
import re
import subprocess
import tempfile
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import potluck_bridge as pb                                    # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def utf8_streams():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):
            pass


utf8_streams()
failures = []


def check(label, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{'  ' + detail if detail else ''}")
    if not ok:
        failures.append(label)


def read(*parts):
    with io.open(os.path.join(ROOT, *parts), encoding="utf-8") as f:
        return f.read()


# ------------------------------------------------------------- a fake world

class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t

    def advance(self, seconds):
        self.t += seconds


class Hub:
    """Records writes and answers however the test wants."""

    def __init__(self, answer=None):
        self.writes = []
        # Four-tuple, matching write_value: the last element says whether a
        # failed value is still worth keeping. A throttle keeps; a refusal
        # does not.
        self.answer = answer or (lambda n, v: (True, 0, "applied", False))

    def __call__(self, name, value):
        self.writes.append((name, value))
        return self.answer(name, value)

    @property
    def names(self):
        return [n for n, _ in self.writes]


def percept(**over):
    """A complete percept: every one of the published values present."""
    p = {
        "schema": 2, "seq": 1, "trigger": "salient", "device_id": "t",
        "attention": {"salience": 0.70, "novel": [], "known": 3},
        "body": {"motion": "still", "posture": "face_up", "steps": 8241,
                 "accel_rms": 0.01, "gyro_rms": 0.01},
        "hearing": {"level_db": -38.2, "peak_db": -12.0,
                    "events": [{"name": "Speech", "conf": 0.79}]},
        "radio": {"place_id": "p3", "ble_count": 4},
        "self": {"battery": 0.63, "battery_temp_c": 31.4, "thermal": "NONE"},
    }
    for key, value in over.items():
        if isinstance(value, dict) and isinstance(p.get(key), dict):
            p[key] = dict(p[key], **value)
        else:
            p[key] = value
    return p


def drive(lines, hub, clock=None, stale_ms=300000, rate=1000.0):
    """Push lines through the bridge with a fake clock and no real waiting."""
    clock = clock or Clock()
    pending = pb.Pending(stale_ms, now=clock)
    budget = pb.Budget(per_second=rate, burst=10000, now=clock)
    pb.run(iter(lines), pending, budget, hub, tick=0,
           sleep=lambda s: clock.advance(s))
    return pending, budget


# ------------------------------------------------- the ordinals, not restated
print("the ordinals come from the Kotlin, not from here")
vocab_kt = read("app", "src", "main", "java", "net", "synthsenses",
                "senses", "Vocabulary.kt")
consts = dict(re.findall(r'const val ([A-Z_]+) = "([^"]+)"', vocab_kt))


def kotlin_list(name):
    m = re.search(r"val %s = listOf\(([^)]*)\)" % name, vocab_kt, re.S)
    out = []
    for tok in re.split(r",\s*", m.group(1).strip()):
        tok = tok.strip()
        if tok:
            out.append(consts.get(tok, tok.strip('"')))
    return out


for name, here in (("MOTION", pb.MOTION), ("POSTURE", pb.POSTURE),
                   ("THERMAL", pb.THERMAL)):
    there = kotlin_list(name)
    check(f"{name} matches Vocabulary.kt exactly",
          here == there, f"bridge={here} kotlin={there}")

# SOUND is the bridge's own vocabulary rather than the phone's, so there is
# no Kotlin to check it against -- but docs/ROADMAP.md states it to a
# consumer that will label a dial with it, which makes that the other copy.
_roadmap = read("docs", "ROADMAP.md")
_doc_sound = re.findall(r"`(\d) ([a-z]+)`", _roadmap)
_doc_sound = [name for num, name in _doc_sound if int(num) < len(pb.SOUND)]
check("the roadmap spells out the sound vocabulary",
      len(_doc_sound) == len(pb.SOUND), str(_doc_sound))
check("and it is the one the bridge publishes",
      _doc_sound == pb.SOUND, f"doc={_doc_sound} code={pb.SOUND}")

check("the sound vocabulary starts at quiet", pb.SOUND[0] == "quiet")
check("and ends at other, the catch-all", pb.SOUND[-1] == "other")
check("every sound bucket is a member of SOUND",
      all(b in pb.SOUND for b, _ in pb.SOUND_MAP))

print("\nthe shape Potluck will accept")
# The one count stated literally, on purpose. Appending a value is a
# change a consumer has pinned and must be told about, so it should cost
# a deliberate edit here rather than sliding through. Everything else
# derives from len(VALUES).
check("eleven values, under the inbox limit of twelve",
      len(pb.VALUES) == 11 and len(pb.VALUES) <= pb.MAX_INBOX_VALUES,
      f"{len(pb.VALUES)} values")
check("every name is unique",
      len({n for n, *_ in pb.VALUES}) == len(pb.VALUES))
kinds = {k for _n, k, *_ in pb.VALUES}
check("every kind is one Potluck can hold in eight bytes",
      kinds <= {"f32", "u32", "u64", "i32", "bool"}, str(sorted(kinds)))
manifest = pb.manifest_outputs()
check("the manifest string is name:kind[:unit] throughout",
      all(re.fullmatch(r"[a-z_]+:[a-z0-9]+(:[a-z]+)?", part)
          for part in manifest.split()), manifest)
check("and names every one of them",
      len(manifest.split()) == len(pb.VALUES), manifest)

# ------------------------------------------------------------- what crosses
print("\nwhat crosses")
v = pb.values_from(percept())
check("a complete percept supplies every value",
      len(v) == len(pb.VALUES), str(sorted(v)))
check("salience crosses as the ratio it is", v["salience"] == 0.70)
check("motion crosses as its ordinal", v["motion"] == 0, str(v.get("motion")))
check("posture too", v["posture"] == 0, str(v.get("posture")))
check("thermal NONE is rung zero", v["thermal"] == 0)
check("p3 becomes 3", v["place"] == 3, str(v.get("place")))
check("steps cross whole", v["steps"] == 8241)
check("speech is recognised", v["sound"] == pb.SOUND.index("speech"))

print("\nwhat does not cross, and must not become zero")
cases = [
    ("no radio at all", {"radio": None}, "place"),
    ("no place learned yet", {"radio": {"place_id": None}}, "place"),
    ("no hearing block", {"hearing": None}, "loudness"),
    ("no battery temperature", {"self": {"battery_temp_c": None}}, "battery_temp"),
    ("thermal UNKNOWN, as on Android 9", {"self": {"thermal": "UNKNOWN"}}, "thermal"),
    ("no attention block", {"attention": None}, "salience"),
    ("no step counter", {"body": {"steps": None}}, "steps"),
]
for label, over, missing in cases:
    got = pb.values_from(percept(**over))
    check(f"{label}: {missing} is absent, not zero",
          missing not in got, f"got {got.get(missing)!r}")

# ble_count is the value this distinction was learned on. Below API 31 a BLE
# scan needs fine location, which the app never requested, so the scan came
# back empty and the count was a confident, permanent zero -- indistinguishable
# on a dial from a genuinely empty room. The fix was in the app; the duty here
# is to keep "the radio did not run" and "the radio ran and saw nothing" as
# different things on the wire.
check("no radio block at all leaves ble_count absent, so Potluck reads NO_DATA",
      "ble_count" not in pb.values_from(percept(radio=None)))
check("but a genuine zero is published, because seeing nothing is a reading",
      pb.values_from(percept(radio={"ble_count": 0}))["ble_count"] == 0)

check("an unknown motion label is absent rather than guessed",
      "motion" not in pb.values_from(percept(body={"motion": "teleporting"})))
check("a bool never crosses as a number",
      "steps" not in pb.values_from(percept(body={"steps": True})))

print("\neach value goes out as the type its manifest declares")
# A percept carrying `"battery": 1` parses to a Python int and serialises
# back as `1`, which a hub reads as a 32-bit integer and an f32 resource can
# refuse. Not an edge case: a fully charged phone reads exactly 1.0, and one
# of the two target phones lives permanently on a charger, so this would have
# been the common reading on that device rather than a rare one.
_whole = percept(**{
    "self": {"battery": 1, "battery_temp_c": 31, "thermal": "NONE"},
    "attention": {"salience": 1},
    "hearing": {"level_db": -30, "events": []},
})
_v = pb.values_from(_whole)
for _name, _kind, _unit, _read, _eps in pb.VALUES:
    if _name not in _v:
        continue
    _want = float if _kind.startswith("f") else int
    check(f"{_name} is a {_want.__name__} on the wire, as {_kind} says",
          type(_v[_name]) is _want,
          f"got {type(_v[_name]).__name__} {_v[_name]!r}")

check("a whole-number f32 serialises with its fraction, not as an integer",
      json.dumps(_v["battery"]) == "1.0", json.dumps(_v["battery"]))
check("and a negative f32 keeps its sign",
      json.dumps(_v["loudness"]) == "-30.0", json.dumps(_v["loudness"]))

# Every unsigned value here is an ordinal or a count. A negative one means a
# bug upstream, and publishing it would hide that behind a plausible reading.
_neg = pb.values_from(percept(body={"steps": -5}))
check("a negative count is dropped rather than published as unsigned",
      "steps" not in _neg, str(_neg.get("steps")))

print("\nwhat the hub says is reported, not what we assume it means")


class _Answer:
    """A hub that answers however the test says, without a socket."""

    base = "https://hub.invalid:8443"

    def __init__(self, status, result=None, headers=None):
        self.status, self.result, self.headers = status, result, headers or {}

    def post(self, path, body):
        return (self.status,
                ({"result": self.result} if self.result else {}),
                self.headers)


# A 404 usually means not provisioned. It does not always: a hub predating
# the fix that taught it to read inbox values answers 404 for a resource that
# IS provisioned, and anyone reading our inference rather than the hub's
# words would go looking in the wrong place. That happened on the evening
# this was provisioned, which is why the wording is quoted now.
_ok, _hold, _note, _ = pb.write_value(
    _Answer(404, "no such resource in this cell (not in its package)"),
    "lab", "battery", 0.5)
check("a 404 repeats the hub's own wording", "no such resource" in _note, _note)
check("and is still a back-off rather than a failure", not _ok and _hold > 0)
check("a 404 with no wording still says something useful",
      "not provisioned" in pb.write_value(_Answer(404), "lab", "x", 1)[2])

_ok, _hold, _note, _ = pb.write_value(
    _Answer(403, "not writable by this program: it is paired as "
                 "'Synth Senses 2' and the writers list names 'Synth Senses'"),
    "lab", "battery", 0.5)
check("a 403 is passed through verbatim, re-pairing hint and all",
      "Synth Senses 2" in _note, _note)

_ok, _hold, _note, _ = pb.write_value(
    _Answer(429, "slow down: ...", {"Retry-After": "7"}), "lab", "battery", 0.5)
check("a 429 honours Retry-After rather than guessing", _hold == 7, str(_hold))

_ok, _hold, _note, _ = pb.write_value(
    _Answer(429, "slow down: ...", {"Retry-After": "nonsense"}),
    "lab", "battery", 0.5)
check("and an unparseable Retry-After does not crash the writer", _hold > 0,
      str(_hold))

_ok, _hold, _note, _ = pb.write_value(
    _Answer(504, "the owning board did not answer"), "lab", "battery", 0.5)
check("a 504 from the owning board is reported and retried later",
      not _ok and _hold > 0 and "did not answer" in _note, _note)

_ok, _hold, _note, _ = pb.write_value(_Answer(200, "applied"), "lab", "battery", 0.5)
check("and a 200 is simply applied", _ok and _note == "applied", _note)

print("\nthe sound curation")
for label, want in (("Speech", "speech"), ("Conversation", "speech"),
                    ("Child speech, kid speaking", "speech"),
                    ("Music", "music"), ("Acoustic guitar", "music"),
                    ("Laughter", "people"), ("Footsteps", "people"),
                    ("Vehicle", "machine"), ("Typing", "machine"),
                    ("Smoke detector, smoke alarm", "machine"),
                    ("Rustling leaves", "other")):
    got = pb.sound_class(percept(hearing={"events": [{"name": label,
                                                      "conf": 0.8}]}))
    check(f"{label!r} -> {want}", got == pb.SOUND.index(want),
          f"got {pb.SOUND[got] if got is not None else None}")
check("ears that heard nothing report quiet, which is a real reading",
      pb.sound_class(percept(hearing={"events": []})) == pb.SOUND.index("quiet"))
check("but no ears at all report nothing",
      pb.sound_class(percept(hearing=None)) is None)

# The three kinds of nothing. An empty event list used to mean all of them,
# and the bridge mapped all of them to `quiet` -- a confident lie whenever
# there was no model, which is the state every CI-built APK ships in.
check("classifier ok with no events really is quiet",
      pb.sound_class(percept(hearing={"events": [], "classifier": "ok"}))
      == pb.SOUND.index("quiet"))
check("classifier absent publishes nothing, not quiet",
      pb.sound_class(percept(hearing={"events": [], "classifier": "absent"}))
      is None)
check("a classifier that threw publishes nothing either",
      pb.sound_class(percept(hearing={"events": [], "classifier": "failed"}))
      is None)
check("and a percept from before the field existed gets the benefit of the doubt",
      pb.sound_class(percept(hearing={"events": []})) == pb.SOUND.index("quiet"))
check("a failed classifier that somehow still named a sound is not published",
      "sound" not in pb.values_from(
          percept(hearing={"events": [{"name": "Speech", "conf": 0.9}],
                           "classifier": "failed"})))
check("the loudest event wins, not the first",
      pb.sound_class(percept(hearing={"events": [
          {"name": "Typing", "conf": 0.3},
          {"name": "Speech", "conf": 0.9}]})) == pb.SOUND.index("speech"))

# ------------------------------------------------------------ the budget
print("\nnewest wins, and a backlog is never replayed")
hub = Hub()
drive([json.dumps(percept())], hub)
check("a first percept writes every value it has",
      len(hub.writes) == len(pb.VALUES),
      f"{len(hub.writes)} writes")

hub = Hub()
drive([json.dumps(percept())] * 5, hub)
check("four identical percepts after it write nothing more",
      len(hub.writes) == len(pb.VALUES), f"{len(hub.writes)} writes")

hub = Hub()
backlog = [json.dumps(percept(seq=i, attention={"salience": i / 2000.0}))
           for i in range(2000)]
clock = Clock()
pending = pb.Pending(300000, now=clock)
budget = pb.Budget(per_second=0, burst=0, now=clock)       # nothing may go out
for line in backlog:
    pending.offer(pb.values_from(json.loads(line)))
check("two thousand spooled percepts leave eleven values pending, not twenty "
      "thousand writes", len(pending.pending) == len(pb.VALUES),
      str(len(pending.pending)))
check("and the pending salience is the newest one",
      abs(pending.pending["salience"] - 1999 / 2000.0) < 1e-9,
      str(pending.pending["salience"]))

print("\nepsilons, so dither does not cost the write budget")
# Each percept is drained before the next is offered. Without that the
# coalescer answers first -- two percepts arriving between writes collapse to
# the newest, which is correct and is tested above, but it means a test that
# simply streams them proves nothing about the epsilon. All three of these
# were written that way at first, and one of them passed for the wrong reason.


def offer_then_drain(hub, percepts, stale_ms=300000):
    clock = Clock()
    pending = pb.Pending(stale_ms, now=clock)
    budget = pb.Budget(per_second=1000.0, burst=10000, now=clock)
    for p in percepts:
        pending.offer(pb.values_from(p))
        pb._drain(pending, budget, hub)
        clock.advance(1)
    return hub


hub = offer_then_drain(Hub(), [percept(),
                               percept(attention={"salience": 0.71}),
                               percept(attention={"salience": 0.72})])
check("a salience wobble under the epsilon is not news",
      hub.names.count("salience") == 1, str(hub.names.count("salience")))

hub = offer_then_drain(Hub(), [percept(),
                               percept(attention={"salience": 0.95})])
check("a real move is", hub.names.count("salience") == 2,
      str(hub.names.count("salience")))

hub = offer_then_drain(Hub(), [percept(),
                               percept(body={"motion": "walking"})])
check("an ordinal that moves is always news, epsilon or not",
      hub.names.count("motion") == 2, str(hub.names.count("motion")))

hub = offer_then_drain(Hub(), [percept(),
                               percept(hearing={"level_db": -38.0}),
                               percept(hearing={"level_db": -20.0})])
check("loudness ignores a decibel of wobble but not eighteen",
      hub.names.count("loudness") == 2, str(hub.names.count("loudness")))

# And the coalescer's own behaviour, stated rather than left implied: two
# changes between writes are one write, of the newer value.
hub = Hub()
clock = Clock()
pending = pb.Pending(300000, now=clock)
budget = pb.Budget(per_second=1000.0, burst=10000, now=clock)
pending.offer(pb.values_from(percept(body={"motion": "walking"})))
pending.offer(pb.values_from(percept(body={"motion": "vehicle"})))
pb._drain(pending, budget, hub)
check("two changes before a write become one write of the newer",
      hub.names.count("motion") == 1
      and dict(hub.writes)["motion"] == pb.MOTION.index("vehicle"),
      str([w for w in hub.writes if w[0] == "motion"]))

print("\nstaying under Potluck's ceiling")
clock = Clock()
budget = pb.Budget(per_second=1.2, burst=3, now=clock)
allowed = 0
for _ in range(20):
    if budget.ready():
        budget.spend()
        allowed += 1
check("a cold start spends only its burst", allowed == 3, str(allowed))
clock.advance(10)
allowed = 0
for _ in range(20):
    if budget.ready():
        budget.spend()
        allowed += 1
check("ten seconds later it has refilled to the burst, not to 12",
      allowed == 3, str(allowed))
check("the default rate is under the hub's two a second",
      pb.Budget().rate < 2.0, str(pb.Budget().rate))

print("\nwhat the hub says back")
clock = Clock()
hub = Hub(answer=lambda n, v: (False, 60, "not provisioned yet (404)", False))
pending = pb.Pending(300000, now=clock)
budget = pb.Budget(per_second=1000.0, burst=10000, now=clock)
pb.run(iter([json.dumps(percept())]), pending, budget, hub, tick=0,
       sleep=lambda s: clock.advance(s))
check("a 404 stops the flood after one attempt rather than trying them all",
      len(hub.writes) == 1, f"{len(hub.writes)} writes")
check("and holds off before trying again",
      budget.blocked_until > clock(), str(budget.blocked_until - clock()))

print("\na throttle is not a refusal")
# The first real 429, on the opening eleven-value burst: "slow down: this
# program is over its share". The bridge printed it correctly and then threw
# the value away, because every failure took the same path. But a 429 does not
# mean the reading is wrong, it means not now -- and dropping it left the cell
# showing an older value until the stale-refresh 150 s later.
for _status, _result, _keep, _why in (
        (200, "applied", False, "applied, nothing to keep"),
        (429, "slow down: ...", True, "throttled, so keep and retry"),
        (504, "the owning board did not answer", True, "transient, so keep"),
        (404, "no such resource in this cell", False, "not provisioned, drop"),
        (403, "not writable by this program", False, "refused, drop")):
    _ok, _hold, _note, _kept = pb.write_value(
        _Answer(_status, _result), "lab", "sound", 5)
    check(f"{_status}: {_why}", _kept == _keep,
          f"keep={_kept} want={_keep}")

# And the drain must act on it: a throttled value stays queued.
_hub = Hub(answer=lambda n, v: (False, 3, "throttled: ...", True))
_clock = Clock()
_pending = pb.Pending(300000, now=_clock)
_budget = pb.Budget(per_second=1000.0, burst=10000, now=_clock)
_pending.offer({"sound": 5})
pb._drain(_pending, _budget, _hub)
check("a throttled value is still pending afterwards",
      _pending.pending.get("sound") == 5, str(_pending.pending))

_hub2 = Hub(answer=lambda n, v: (False, 30, "refused (403): ...", False))
_pending2 = pb.Pending(300000, now=Clock())
_budget2 = pb.Budget(per_second=1000.0, burst=10000, now=Clock())
_pending2.offer({"sound": 5})
pb._drain(_pending2, _budget2, _hub2)
check("a refused value is dropped rather than retried forever",
      "sound" not in _pending2.pending, str(_pending2.pending))

check("the default burst leaves headroom under the hub's four",
      pb.Budget().burst <= 2, str(pb.Budget().burst))

print("\nreplies are not percepts")
hub = Hub()
drive([json.dumps({"type": "ack", "cmd": "look", "ok": True}),
       json.dumps({"type": "status", "device": {"model": "x"}}),
       json.dumps(percept())], hub)
check("a reply on the stream is ignored, a percept is not",
      len(hub.writes) == len(pb.VALUES), f"{len(hub.writes)} writes")

print("\nfollowing a log instead of a pipe")
# A PowerShell pipeline between two native commands did not stream: the bridge
# sat silent for two hours beside a working receiver, with the desk still
# showing a one-shot test write from earlier. The receiver already appends
# every percept to its log, so tailing that carries the same stream without a
# pipe -- and it decouples the two, so restarting the receiver no longer takes
# the bridge down with it.
_log = os.path.join(tempfile.mkdtemp(), "follow.jsonl")
with io.open(_log, "w", encoding="utf-8") as f:
    for i in range(3):
        f.write(json.dumps(percept(seq=i)) + "\n")

_lines = list(itertools.islice(pb.tail(_log, from_start=True, poll_s=0), 3))
check("following from the start yields what is already there",
      len(_lines) == 3, str(len(_lines)))
check("and each line is a whole percept",
      all(json.loads(l).get("schema") == 2 for l in _lines))

# From the end, the existing lines are skipped. History is not replayed,
# because Potluck holds a current value and its age, not a series.
_gen = pb.tail(_log, from_start=False, poll_s=0)
with io.open(_log, "a", encoding="utf-8") as f:
    f.write(json.dumps(percept(seq=99)) + "\n")
_fresh = next(_gen)
check("following from the end skips history and yields only what is new",
      json.loads(_fresh).get("seq") == 99, _fresh[:60])

# A line written in two pieces, which is what a reader can see mid-append.
_part = os.path.join(tempfile.mkdtemp(), "partial.jsonl")
io.open(_part, "w", encoding="utf-8").write("")
_gen2 = pb.tail(_part, from_start=True, poll_s=0)
_whole = json.dumps(percept(seq=7))
with io.open(_part, "a", encoding="utf-8") as f:
    f.write(_whole[:20])
    f.flush()
with io.open(_part, "a", encoding="utf-8") as f:
    f.write(_whole[20:] + "\n")
    f.flush()
check("a half-written line is not yielded until its newline arrives",
      json.loads(next(_gen2)).get("seq") == 7)

print("\nrubbish on the stream")
hub = Hub()
drive(["", "   ", "not json at all", "[1,2,3]", '"a string"',
       json.dumps(percept())], hub)
check("junk lines are skipped and the percept still lands",
      len(hub.writes) == len(pb.VALUES), f"{len(hub.writes)} writes")

print("\nrefreshing before Potluck calls us stale")
clock = Clock()
hub = Hub()
pending = pb.Pending(10000, now=clock)                      # stale_ms 10 s
budget = pb.Budget(per_second=1000.0, burst=10000, now=clock)
pending.offer(pb.values_from(percept()))
pb._drain(pending, budget, hub)
check("everything goes out once", len(hub.writes) == len(pb.VALUES),
      str(len(hub.writes)))
clock.advance(2)
pb._drain(pending, budget, hub)
check("nothing is rewritten two seconds later",
      len(hub.writes) == len(pb.VALUES),
      str(len(hub.writes)))
clock.advance(4)                                            # now past half
pb._drain(pending, budget, hub)
check("but everything is refreshed at half of stale_ms, so a quiet room "
      "never reads STALE", len(hub.writes) == 2 * len(pb.VALUES),
      str(len(hub.writes)))

print("\nthe contract a consumer checks itself against")
# A consumer that renders these numbers keeps a copy of the words, and a copy
# drifts: a desktop view came to label ordinal 3 "impact" while this bridge
# meant "people", carried over from a proposal of mine that was superseded
# without anyone noticing. No ordinal had moved, so reporting "no ordinal
# moved" was true and useless -- the words had.
#
# Reaching into that consumer's files from here would invert the dependency
# and would skip in CI whenever the other project is not checked out, which
# is worse than no test. So the contract is published instead and each
# consumer asserts its own copy against it. That only works if what is
# published is complete, which is what these check.
_vocab = json.loads(subprocess.run(
    [sys.executable,
     os.path.join(os.path.dirname(os.path.abspath(__file__)),
                  "potluck_bridge.py"), "--vocabulary"],
    capture_output=True, text=True, encoding="utf-8").stdout)

check("the contract names every value",
      [v["name"] for v in _vocab["values"]] == [n for n, *_ in pb.VALUES],
      str([v["name"] for v in _vocab["values"]]))
check("with their kinds and units",
      [(v["kind"], v["unit"]) for v in _vocab["values"]]
      == [(k, u) for _n, k, u, _r, _e in pb.VALUES])
for _name, _table in (("motion", pb.MOTION), ("posture", pb.POSTURE),
                      ("thermal", pb.THERMAL), ("sound", pb.SOUND)):
    check(f"and publishes the {_name} ordinals in order",
          _vocab["ordinals"][_name] == _table,
          str(_vocab["ordinals"].get(_name)))
check("and says which readings are absent rather than zero",
      set(_vocab["absent"]) == {"thermal", "sound"},
      str(sorted(_vocab["absent"])))
check("and tells the reader the rule that makes ordinals safe",
      "append, never insert" in _vocab["note"], _vocab.get("note", "")[:80])

print("\nthe published table and the documented one are the same table")
# docs/ROADMAP.md states these to a consumer that will hard-code them, and
# Potluck's own INTEGRATIONS.md records them on the other side. Three copies
# of one fact, which is how `steps` came to be u64 in the document and u32
# here -- agreed as u64, written as u32, and nothing said so.
roadmap = read("docs", "ROADMAP.md")
doc_rows = {}
for line in roadmap.splitlines():
    m = re.match(r"\|\s*`(\w+)`\s*\|\s*(\w+)\s*\|\s*([^|]*?)\s*\|", line)
    if m and m.group(2) in ("f32", "u32", "u64", "i32", "bool"):
        unit = m.group(3).strip()
        doc_rows[m.group(1)] = (m.group(2), None if unit in ("", "\u2014") else unit)

here = {n: (k, u) for n, k, u, _r, _e in pb.VALUES}
check("the roadmap documents a value table at all",
      len(doc_rows) == len(pb.VALUES),
      f"found {len(doc_rows)}: {sorted(doc_rows)}")
check("the same ten names", set(doc_rows) == set(here),
      f"doc-only={sorted(set(doc_rows) - set(here))} "
      f"code-only={sorted(set(here) - set(doc_rows))}")
for name in sorted(set(doc_rows) & set(here)):
    check(f"{name} has the documented type and unit",
          doc_rows[name] == here[name],
          f"doc={doc_rows[name]} code={here[name]}")

print("\nasking to pair, and waiting for a human")
# The first real pairing took three goes, and neither failure was pairing's
# fault: the Allow button was behind another window, and then the five-minute
# window lapsed while we looked for it. Both are ceremony, and ceremony is
# the part a program should absorb. The click itself is not -- approval is a
# human act on the hub's own PC, checked against the address the request came
# from, and a program that approved its own request would make the whole
# mechanism pointless.


class _Pairing:
    """A hub that expires `lapses` requests, then answers `end`."""

    base = "https://hub.invalid:8443"

    def __init__(self, lapses=0, end="approved"):
        self.lapses, self.end = lapses, end
        self.asks = 0

    def post(self, path, body):
        self.asks += 1
        return 202, {"pair_id": "id%d" % self.asks, "expires_in_s": 300}, {}

    def get(self, path):
        if self.asks > self.lapses:
            return 200, {"state": self.end, "token": "a-token"}, {}
        return 404, {}, {}


_h = _Pairing(lapses=0)
check("an approval on the first ask needs only one request",
      pb.pair(_h, "Synth Senses", "write", poll_s=0, timeout_s=0.01)
      == "a-token" and _h.asks == 1, f"{_h.asks} asks")

_h = _Pairing(lapses=2)
_tok = pb.pair(_h, "Synth Senses", "write", poll_s=0, timeout_s=0.01)
check("a lapsed window is re-asked rather than retyped",
      _tok == "a-token" and _h.asks == 3, f"{_h.asks} asks, token {_tok!r}")

_h = _Pairing(lapses=9)
check("but it gives up rather than pestering forever",
      pb.pair(_h, "Synth Senses", "write", poll_s=0, timeout_s=0.01) is None
      and _h.asks == 3, f"{_h.asks} asks")

_h = _Pairing(lapses=0, end="denied")
check("a denial is final and is never asked again",
      pb.pair(_h, "Synth Senses", "write", poll_s=0, timeout_s=0.01) is None
      and _h.asks == 1, f"{_h.asks} asks")


class _Refuses:
    base = "https://hub.invalid:8443"

    def post(self, path, body):
        return 429, {"result": "too many pending requests"}, {}


check("a hub that refuses the request says so and stops",
      pb.pair(_Refuses(), "Synth Senses", "write", poll_s=0,
              timeout_s=0.01) is None)

print("\nthe token does not travel to an unverified hub")
# The hub serves a certificate of its own making, so verify_mode is
# necessarily NONE and the pin is the only thing standing between a bearer
# token and whoever answers on that address. This block exists because the
# first version of this file described the pin in a docstring and never
# called the function that checks it -- the mechanism was decorative, and
# nothing would have said so.


class FakeHub(pb.Hub):
    """A hub whose certificate we can state rather than serve."""

    def __init__(self, serving, **kw):
        super().__init__("https://hub.invalid:8443", **kw)
        self.serving = serving
        self.asked = 0

    def peer_fingerprint(self):
        self.asked += 1
        return self.serving


good = "a" * 64
other = "b" * 64

hub = FakeHub(good, token="t", fingerprint=good)
try:
    hub.check_pin()
    ok, why = True, ""
except RuntimeError as e:
    ok, why = False, str(e)
check("a matching pin lets the token through", ok, why)

hub = FakeHub(other, token="t", fingerprint=good)
try:
    hub.check_pin()
    ok = False
except RuntimeError as e:
    ok = "not the one this token was paired with" in str(e)
check("a different certificate refuses, and says what happened", ok)

hub = FakeHub(good, token="t", fingerprint=None)
try:
    hub.check_pin()
    ok = False
except RuntimeError as e:
    ok = "no certificate pin recorded" in str(e)
check("no pin at all also refuses, rather than sending it anyway", ok)

hub = FakeHub(other, token="t", fingerprint=good, insecure=True)
try:
    hub.check_pin()
    ok = True
except RuntimeError:
    ok = False
check("--insecure is the deliberate way out, and it works", ok)

hub = FakeHub(good, token="t", fingerprint=good)
hub.check_pin()
hub.check_pin()
hub.check_pin()
check("the certificate is fetched once, not on every write",
      hub.asked == 1, f"asked {hub.asked} times")

check("pairing itself carries no token, which is how the pin gets recorded",
      pb.Hub("https://h:8443").token is None)

# A Hub holding a token checks its pin before every request. Pairing must
# therefore not be done on such a Hub: with no pin recorded yet, check_pin
# refuses, and the one action that would record a pin becomes impossible.
hub = FakeHub(good, token="an old token", fingerprint=None)
try:
    hub.post("/api/pair", {"name": "x", "right": "write"})
    ok = False
except RuntimeError as e:
    ok = "no certificate pin recorded" in str(e)
except Exception:
    ok = False
check("a hub carrying a token cannot pair, which is why main() builds a "
      "fresh one", ok)

check("host and port are parsed from the base url",
      pb.Hub("https://10.0.0.5:8443").host_port() == ("10.0.0.5", 8443),
      str(pb.Hub("https://10.0.0.5:8443").host_port()))
check("and https defaults to 443 when no port is given",
      pb.Hub("https://hub.lan").host_port() == ("hub.lan", 443))

print(f"\n{len(failures)} failure(s)")
for f in failures:
    print("  -", f)
sys.exit(1 if failures else 0)
