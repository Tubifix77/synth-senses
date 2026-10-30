#!/usr/bin/env python3
"""Publish a phone's percepts into a Potluck cell, as eleven numbers.

Potluck is a separate project of the author's: a runtime that federates
ESP32-S3 boards, and optionally a PC, into one addressable machine whose
boards pool their sensors. A phone with a camera, a microphone, a barometer
and three radios is a richer node than any board in that cell, which is why
this exists.

It consumes percepts; it cannot consume them whole. A Potluck inbox value is
at most **eight bytes** -- a bool or a number -- so labels, transcripts and
the narration have no representation there and stay on this side. What
crosses is eleven numbers, listed in VALUES below.

    python3 tools/receiver.py | python3 tools/potluck_bridge.py --dry-run

Run it against a real cell by pairing once, then pointing it at the hub:

    python3 tools/potluck_bridge.py --hub https://192.168.1.10:8443 --pair
    python3 tools/receiver.py | python3 tools/potluck_bridge.py \\
        --hub https://192.168.1.10:8443

Nothing is provisioned until the cell's owner says so, and until then every
write answers 404. That is a state, not an error: this program can be built
and exercised long before any of it is live, which is exactly what --dry-run
is for.

The contract it is written against is Potluck's own INTEGRATIONS.md step 7
and HUB-API.md, read rather than remembered. The numbers that follow from
them -- two writes a second in all, at most twelve values per inbox, publish
now and never history -- are enforced here rather than hoped for.
"""
import argparse
import hashlib
import io
import json
import os
import socket
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request


def utf8_streams():
    """Make stdout and stderr UTF-8, whatever the platform thinks.

    A percept's narration can hold any character at all, and this program's
    stdin is a pipe from the receiver. On Windows a pipe defaults to cp1252,
    so without this the feed dies on the first word someone says that cannot
    be spelled in it. CI is Linux and would never show it.
    """
    for stream in (sys.stdout, sys.stderr, sys.stdin):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):
            pass


def say(*args):
    """Everything for a person. stdout stays clean for machine output."""
    print(*args, file=sys.stderr)
    sys.stderr.flush()


# --------------------------------------------------------- the vocabularies
#
# These mirror app/src/main/java/net/synthsenses/senses/Vocabulary.kt, where
# the order is a wire contract: a consumer that cannot carry strings carries
# the index instead. Appending is safe; inserting silently relabels every
# value after it on someone else's board. VocabularyTest pins the Kotlin side
# and test_potluck_bridge.py pins this one; they are checked against each
# other so a change to either fails rather than diverging quietly.

MOTION = ["still", "handled", "walking", "vehicle"]

POSTURE = ["face_up", "face_down", "upright", "tilted", "pocket", "unknown"]

THERMAL = ["NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY",
           "SHUTDOWN"]

# Every closed vocabulary this project has. Which of them are *published*
# depends on which values are in VALUES, so --vocabulary filters this rather
# than restating it: thermal is still a real ladder and VocabularyTest still
# pins its order, but nothing carries it as a number since the coordinates
# took its slot, and a contract that lists it says two things at once.
#
# Defined below the lists it names, so it is assembled after them.

# This one is ours, not the phone's. YAMNet names hundreds of classes and a
# board can hold one small number, so they are collapsed to the distinction a
# room actually cares about. Order is a contract exactly as above.
SOUND = ["quiet", "speech", "music", "people", "machine", "other"]

# Which YAMNet labels land in which bucket. Matched case-insensitively on the
# whole label first, then as a substring, so "Conversation" and "Child speech,
# kid speaking" both reach `speech` without listing every variant.
SOUND_MAP = (
    # The classifier's own "Silence" class matched nothing below and went out
    # as `other` -- 7 of 60 samples on an evening with the TV on, so a mind
    # reading this cell was told an unidentified sound was there exactly when
    # the classifier had said there was none.
    ("quiet", ("silence",)),
    ("speech", ("speech", "conversation", "narration", "whispering", "shout",
                "singing", "speech synthesizer")),
    ("music", ("music", "musical instrument", "guitar", "piano", "drum",
               "singing bowl")),
    ("people", ("laughter", "cough", "sneeze", "footsteps", "clapping",
                "breathing", "chewing", "crying", "baby")),
    ("machine", ("vehicle", "engine", "car", "traffic", "alarm", "siren",
                 "telephone", "doorbell", "typing", "keyboard", "printer",
                 "vacuum", "fan", "air conditioning", "water tap", "dishes",
                 "microwave", "television", "radio")),
)



#: value name -> the word list its ordinal indexes into.
VOCABULARIES = {
    "motion": MOTION,
    "posture": POSTURE,
    "thermal": THERMAL,
    "sound": SOUND,
}

#: value name -> why a reading can be absent rather than zero.
ABSENT_NOTES = {
    "thermal": "UNKNOWN has no ordinal: it means the platform would not say. "
               "Permanently absent below API 29.",
    "sound": "quiet is a real reading; no hearing block at all is absence "
             "and is never written.",
    "lat": "written only while the user has location switched on, and only "
           "from a fix at most GPS_MAX_AGE_S old.",
    "lon": "written only while the user has location switched on, and only "
           "from a fix at most GPS_MAX_AGE_S old.",
    "ble_count": "absent when the phone could not look: BLE off or denied, a "
                 "scan refused for starting too soon after the last five, or "
                 "the screen off on Android 13+ with nothing heard. A written "
                 "zero is always a real empty room.",
}

# ---------------------------------------------------------- the published values
#
# name, kind, unit, and how to read it out of a percept. `kind` is what
# Potluck's manifest should declare; `unit` is from its closed enum, or None
# where the number is a bare ratio or index.

def _idx(table, value):
    """The ordinal of a label, or None if it is not one we publish."""
    try:
        return table.index(value)
    except (ValueError, AttributeError):
        return None


def _loudness(percept):
    """The interval's energy-mean level when the phone sent one, else this sample's."""
    mean = _dig(percept, "hearing", "recent", "level_db_mean")
    if isinstance(mean, (int, float)) and not isinstance(mean, bool):
        return mean
    return _dig(percept, "hearing", "level_db")


def _dig(percept, *path):
    """percept["a"]["b"], or None the moment anything is missing.

    Every sense block is nullable by design -- a phone with no radio sends
    `"radio": null` rather than inventing one -- so this is the normal case,
    not an error path.
    """
    cur = percept
    for key in path:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(key)
    return cur


def sound_class(percept):
    """What the room sounded like: from the interval if the phone sent one.

    A phone build with `hearing.recent` tallies every sample since the last
    percept, so the dominant category over that interval is the honest answer.
    Counted by category rather than by label, because speech arrives under
    several names ("Speech", "Child speech, kid speaking", "Conversation") and
    a raw-label count would let one-word Silence outvote it. Samples with no
    nameable sound are left out of the vote; an interval with nothing nameable
    at all is quiet. A tie goes to the sound rather than to quiet, since half
    an interval of speech is something that happened.
    """
    recent = _dig(percept, "hearing", "recent")
    if isinstance(recent, dict):
        top = recent.get("top")
        classified = recent.get("classified")
        if isinstance(top, dict) and isinstance(classified, int):
            if classified <= 0:
                return None
            votes = {}
            for label, n in top.items():
                if isinstance(n, int) and n > 0:
                    b = _bucket(label)
                    votes[b] = votes.get(b, 0) + n
            if not votes:
                return SOUND.index("quiet")
            best = max(votes.items(), key=lambda kv: (kv[1], kv[0] != "quiet"))
            return SOUND.index(best[0])
    return _sound_of_sample(percept)


def _sound_of_sample(percept):
    """Collapse the heard events to one of SOUND, or None if we cannot tell.

    `quiet` is a real reading and is published: it means the ears worked and
    heard nothing worth naming. No hearing block at all is absence, and
    absence is never written -- that is Potluck's NO_DATA, and it is the
    degrade-never-fabricate invariant expressed in someone else's types.
    """
    hearing = percept.get("hearing")
    if not isinstance(hearing, dict):
        return None
    # "ok" means the classifier ran and genuinely heard nothing above
    # threshold, so an empty list really does mean quiet. "absent" means
    # there is no model and "failed" means it threw -- in both of those an
    # empty list means we cannot hear classes at all, and publishing `quiet`
    # would be the same confident lie as a radio that never scanned
    # reporting zero devices. A percept from before this field existed says
    # nothing, and is given the benefit of the doubt.
    state = hearing.get("classifier")
    if state is not None and state != "ok":
        return None
    events = hearing.get("events")
    if not isinstance(events, list) or not events:
        return SOUND.index("quiet")
    best = None
    for event in events:
        if not isinstance(event, dict):
            continue
        name = str(event.get("name", "")).lower()
        conf = event.get("conf")
        if not isinstance(conf, (int, float)):
            continue
        if best is None or conf > best[1]:
            best = (name, conf)
    if best is None:
        return SOUND.index("quiet")
    return SOUND.index(_bucket(best[0]))


def _bucket(name):
    """One YAMNet label -> one SOUND word."""
    name = str(name).lower()
    for bucket, needles in SOUND_MAP:
        for needle in needles:
            if needle in name:
                return bucket
    return "other"


#: A fix older than this is where the phone was, not where it is. The phone
#: asks for a fix a minute, and the slowest first fix measured indoors took
#: twelve, so fifteen minutes is generous to a working feed and still far
#: short of what went wrong: from 2026-10-24 the P20 stopped receiving fixes
#: and kept sending its last one, honestly aged, for five days -- and this
#: bridge wrote it into Potluck as current every time, because it never
#: looked at `gps.age_s`. Absent the age (schema 2 had none), the fix is used.
GPS_MAX_AGE_S = 900


def _fix(percept, key):
    """gps[key] from a fix fresh enough to be the phone's position now."""
    age = _dig(percept, "gps", "age_s")
    if isinstance(age, (int, float)) and not isinstance(age, bool) and age > GPS_MAX_AGE_S:
        return None
    return _dig(percept, "gps", key)


def place_number(percept):
    """`p3` -> 3. The ids are assigned in first-seen order and persisted.

    They are stable across restarts but NOT across `forget {"what":"places"}`,
    which resets the counter so the numbers get reused for different rooms. A
    board would carry on captioning "place 3" meaning somewhere else. There is
    no fix available on the Potluck side, because the honest repair is a place
    *name* and a name is a string. Documented in docs/ROADMAP.md; read the
    board's place labels as provisional after a reset.
    """
    pid = _dig(percept, "radio", "place_id")
    if not isinstance(pid, str) or not pid.startswith("p"):
        return None
    try:
        return int(pid[1:])
    except ValueError:
        return None


def thermal_number(percept):
    """The thermal ladder as an ordinal, or None for UNKNOWN.

    UNKNOWN is not a rung: it means the platform would not say, which is
    absence. Below API 29 it is the only answer there is, so on an Android 9
    phone this value is simply never written -- correct, and visible in
    Potluck as NO_DATA rather than as a confident `NONE`.
    """
    return _idx(THERMAL, _dig(percept, "self", "thermal"))


# Each entry: (name, kind, unit, reader, epsilon)
#
# Called VALUES rather than VALUES because it is eleven now and will not stay
# still. Potluck's inbox holds at most twelve, which is the real ceiling.
#
# `epsilon` is how much a float must move before it is worth a write. This is
# the same reasoning as Percept.tokens() bucketing its continuous quantities:
# a room at 310 lux and the same room at 340 lux must not look like news. Here
# the cost of pretending otherwise is the write budget rather than habituation.
VALUES = [
    ("salience",     "f32", "ratio",   lambda p: _dig(p, "attention", "salience"), 0.05),
    ("motion",       "u32", None,      lambda p: _idx(MOTION, _dig(p, "body", "motion")), 0),
    ("posture",      "u32", None,      lambda p: _idx(POSTURE, _dig(p, "body", "posture")), 0),
    # u64 because that is what Potluck's manifest records. The value counts
    # from the last reboot rather than from install -- TYPE_STEP_COUNTER's
    # own contract -- so it drops to zero when the phone restarts and
    # anything differencing it sees a large negative.
    ("steps",        "u64", "count",   lambda p: _dig(p, "body", "steps"), 0),
    ("sound",        "u32", None,      sound_class, 0),
    ("loudness",     "f32", None,      lambda p: _loudness(p), 1.5),
    ("place",        "u32", None,      place_number, 0),
    ("battery",      "f32", "ratio",   lambda p: _dig(p, "self", "battery"), 0.02),
    ("battery_temp", "f32", "celsius", lambda p: _dig(p, "self", "battery_temp_c"), 0.5),
    # Latitude and longitude, when the user has switched location on. Left
    # out of the first eleven, which was a bug rather than a trade: the app
    # transmits exact coordinates the moment the toggle is on, and a bridge
    # that silently drops them breaks the user's stated intent somewhere they
    # cannot see.
    #
    # The epsilon is 0.0002 degrees, about twenty metres. A stationary phone's
    # fix wanders by tens of metres and the accuracy estimate here is around
    # forty, so a tighter threshold would spend the write budget on noise
    # without telling anyone anything. Precision is not affected -- what is
    # written is what the phone reported.
    ("lat",          "f32", "degree",  lambda p: _fix(p, "lat"), 0.0002),
    ("lon",          "f32", "degree",  lambda p: _fix(p, "lon"), 0.0002),
    # Approved for the first rollout on 2026-10-21. Appended rather than
    # inserted, which is what keeps every other ordinal where it was and makes
    # this non-breaking for a consumer that has already pinned the list.
    #
    # The epsilon is 2 because a BLE count dithers: a phone in someone's
    # pocket walks past and the number moves by one, which is not news and
    # would cost a write every sample. `Percept.tokens()` reached the same
    # conclusion independently and buckets this field for habituation rather
    # than using it raw.
    ("ble_count",    "u32", "count",   lambda p: _dig(p, "radio", "ble_count"), 2),
]

MAX_INBOX_VALUES = 12          # Potluck INTEGRATIONS.md step 7
assert len(VALUES) <= MAX_INBOX_VALUES, (
    "an inbox holds at most %d values; this is %d" % (MAX_INBOX_VALUES, len(VALUES)))


def values_from(percept):
    """Every value this percept can supply, by name. Missing ones are absent.

    Absent means absent: the key is not in the dict, so nothing is written and
    Potluck keeps reading NO_DATA. A phone with no radio must not publish
    `place 0`.
    """
    out = {}
    for name, kind, _unit, read, _eps in VALUES:
        try:
            value = read(percept)
        except Exception:
            value = None
        if value is None or isinstance(value, bool):
            continue
        if not isinstance(value, (int, float)):
            continue
        # Send the declared type, rather than whatever JSON the number
        # happened to arrive as. A percept carrying `"battery": 1` parses to
        # a Python int and serialises back as `1`, which a hub reads as a
        # 32-bit integer and an f32 resource can refuse. That is not an edge
        # case here: a fully charged phone reads exactly 1.0, and one of the
        # two target phones lives permanently on a charger. Potluck now
        # coerces to the declared type on its side too, but depending on
        # someone else's coercion for something statable exactly is the kind
        # of thing that works until it does not.
        if kind.startswith("f"):
            value = float(value)
        else:
            if value < 0:
                # Every unsigned value here is an ordinal or a count. A
                # negative one means a bug upstream, and publishing it as a
                # huge unsigned number would hide that behind a plausible
                # reading.
                continue
            value = int(value)
        out[name] = value
    return out


def manifest_outputs():
    """The `outputs` string for Potluck's manifest, so it is never hand-typed.

    Format is `name:kind:unit` per INTEGRATIONS.md, unit omitted where there
    is none.
    """
    parts = []
    for name, kind, unit, _read, _eps in VALUES:
        parts.append(f"{name}:{kind}:{unit}" if unit else f"{name}:{kind}")
    return " ".join(parts)


def changed(name, old, new, eps):
    """Is this worth a write?

    The write budget is two a second for everything, so dither is expensive.
    Integers are compared exactly -- an ordinal that moves is news by
    definition -- and floats must clear their epsilon.
    """
    if old is None:
        return True
    if eps:
        try:
            return abs(float(new) - float(old)) >= eps
        except (TypeError, ValueError):
            return new != old
    return new != old


class Pending:
    """Newest-wins per path, with a refresh before Potluck calls us stale.

    Potluck holds a current value and its age, not a series. So this is a map
    of at most ten entries rather than a queue of writes: when a value changes
    twice before either reaches the hub, the older one is not a backlog item,
    it is simply wrong and is dropped. That bound matters -- eleven values at two
    writes a second takes five seconds to drain, which is the whole default
    sample interval, so an append-only queue could never catch up.
    """

    def __init__(self, stale_ms, now=None):
        self.stale_s = stale_ms / 1000.0
        self.pending = {}        # name -> value waiting to go
        self.written = {}        # name -> value the hub last accepted
        self.written_at = {}     # name -> when that happened
        self.seen_at = {}        # name -> when the phone last reported it
        self.held = {}           # name -> don't try this one again before
        self.now = now or time.monotonic
        # The reader offers while the drainer takes. Reentrant because
        # refresh_due() is called from inside the drain loop.
        self.lock = threading.RLock()

    def offer(self, values):
        """Take a percept's worth of values; queue only what is worth sending."""
        eps_of = {n: e for n, _k, _u, _r, e in VALUES}
        now = self.now()
        with self.lock:
            for name, value in values.items():
                # Reported, whether or not it changed: that is what entitles a
                # value to be refreshed. See refresh_due.
                self.seen_at[name] = now
                if changed(name, self.written.get(name), value,
                           eps_of.get(name, 0)):
                    self.pending[name] = value

    def refresh_due(self):
        """Values that will age into STALE unless rewritten.

        Half of stale_ms, so one missed write is not a false alarm on the
        board. A quiet room is the normal case here -- habituation means the
        phone may change nothing for minutes -- and a healthy silent source must
        not read STALE.

        **But only a value the phone is still reporting.** This used to refresh
        everything ever written, from its last written value, forever. So a
        sense switched off on the phone went on being rewritten into Potluck
        as GOOD with a small age: the last "speech" replayed as current, and
        with location off the last fix refreshed indefinitely -- the twenty-hour
        replay again, one layer up. A healthy phone re-reports every live value
        at least every heartbeat, two minutes, which is inside this window, so
        the quiet room is unaffected. A value it has stopped reporting is left
        to age into STALE, which is then the truth: the phone stopped saying.
        """
        now = self.now()
        with self.lock:
            for name, value in list(self.written.items()):
                if name in self.pending:
                    continue
                if now - self.seen_at.get(name, float("-inf")) > self.stale_s / 2:
                    continue
                if now - self.written_at.get(name, 0) >= self.stale_s / 2:
                    self.pending[name] = value

    def take(self):
        """One (name, value) to write, or None. Oldest-written first.

        A path standing down under its own hold is skipped rather than
        returned. That is what stops a single unwritable value from sitting at
        the head of the queue forever: `written_at` defaults to 0, so a value
        that has **never** been written sorts first by construction, and an
        unprovisioned path is exactly that.
        """
        now = self.now()
        with self.lock:
            ready = [n for n in self.pending if self.held.get(n, 0) <= now]
            if not ready:
                return None
            name = min(ready, key=lambda n: self.written_at.get(n, 0))
            return name, self.pending[name]

    def hold(self, name, seconds):
        """Stand one path down, leaving every other path writable."""
        with self.lock:
            self.held[name] = max(self.held.get(name, 0), self.now() + seconds)

    def accepted(self, name, value):
        with self.lock:
            self.pending.pop(name, None)
            self.held.pop(name, None)
            self.written[name] = value
            self.written_at[name] = self.now()

    def rejected(self, name):
        """Drop it. A newer value will come; a retry loop would not help."""
        with self.lock:
            self.pending.pop(name, None)
            self.held.pop(name, None)


class Budget:
    """A token bucket, deliberately under Potluck's ceiling.

    The hub allows two writes a second with bursts of four, and answers 429
    with a Retry-After over that. Running at exactly the ceiling means living
    on the edge of being throttled, so this sits below it and leaves the burst
    allowance as headroom rather than as the plan.

    **The share is counted per pairing name, not per process.** Two bridges
    holding the same token look to the hub like one program writing at twice
    the rate, and the first real 429 was exactly that -- two copies running at
    once, eleven values each, against a budget for eleven. No amount of
    tuning here fixes that; run one bridge.
    """

    def __init__(self, per_second=1.2, burst=2, now=None):
        self.rate = per_second
        self.burst = burst
        self.tokens = float(burst)
        self.now = now or time.monotonic
        self.last = self.now()
        self.blocked_until = 0.0

    def hold_off(self, seconds):
        """Honour a Retry-After. Program-wide, so only a throttle belongs here.

        A 404 used to back off through this, which was a real defect: the
        budget gates every path, so one unprovisioned value stood the whole
        writer down. Per-path holds live on `Pending` now.
        """
        self.blocked_until = max(self.blocked_until, self.now() + seconds)

    def ready(self):
        now = self.now()
        if now < self.blocked_until:
            return False
        self.tokens = min(self.burst, self.tokens + (now - self.last) * self.rate)
        self.last = now
        return self.tokens >= 1.0

    def spend(self):
        self.tokens -= 1.0


# ------------------------------------------------------------------- the hub

class Hub:
    """The smallest client that is still honest about TLS.

    The hub serves a certificate of its own making, so there is nothing for a
    public CA to vouch for. Potluck's answer is that a client pins the
    fingerprint it paired with, and that is what this does: the fingerprint is
    stored beside the token and checked on every connection, so the token only
    ever travels to the certificate it was issued by.
    """

    def __init__(self, base, token=None, fingerprint=None, insecure=False):
        self.base = base.rstrip("/")
        self.token = token
        self.fingerprint = fingerprint
        self.insecure = insecure
        self._pin_ok = False       # checked once, not on every write

    def _context(self):
        ctx = ssl.create_default_context()
        # Pinning replaces the chain, so the chain's own checks are not the
        # thing being relied on. Without a pin and without --insecure we do
        # not connect at all.
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx

    def host_port(self):
        rest = self.base.split("://", 1)[-1]
        host, _, port = rest.partition(":")
        return host, int(port or (443 if self.base.startswith("https") else 80))

    def peer_fingerprint(self):
        """The SHA-256 of the certificate this hub is serving right now."""
        host, port = self.host_port()
        ctx = self._context()
        with socket.create_connection((host, port), timeout=10) as raw:
            with ctx.wrap_socket(raw, server_hostname=host) as tls:
                der = tls.getpeercert(binary_form=True)
        return hashlib.sha256(der).hexdigest()

    def check_pin(self):
        """Refuse to send the token anywhere but the certificate it came from.

        The hub serves a certificate of its own making, so there is no chain
        for a public CA to vouch for and `verify_mode` is necessarily NONE.
        That is only safe because of this: the fingerprint recorded at pairing
        is checked before the bearer token is ever put on the wire.

        Three outcomes, and the third is the one that matters. A pin that
        matches proceeds. A pin that does not raises. **No pin at all also
        raises**, because sending a bearer token to an unverified endpoint is
        the thing the pin exists to prevent, and quietly doing it anyway would
        make the whole mechanism decorative. `--insecure` is the deliberate
        way out, and it says what it is.
        """
        if self._pin_ok or not self.base.startswith("https"):
            return
        if self.insecure:
            self._pin_ok = True
            return
        if not self.fingerprint:
            raise RuntimeError(
                "no certificate pin recorded for this hub, so the pairing "
                "token will not be sent. Pair again to record one, or pass "
                "--insecure if you know the network is yours."
            )
        got = self.peer_fingerprint()
        if got.lower() != self.fingerprint.lower():
            raise RuntimeError(
                "the hub's certificate is not the one this token was paired "
                f"with (pinned {self.fingerprint[:16]}..., serving "
                f"{got[:16]}...). Refusing to send the token. Pair again if "
                "the hub was legitimately reissued."
            )
        self._pin_ok = True

    def post(self, path, body):
        url = self.base + path
        data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        # Every POST needs this header; a request without it is refused with
        # 403 "missing X-Potluck-Request". It is the hub's CSRF guard.
        req.add_header("X-Potluck-Request", "1")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        return self._send(req)

    def get(self, path):
        req = urllib.request.Request(self.base + path, method="GET")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        return self._send(req)

    def _send(self, req):
        """-> (status, parsed body or {}). Network trouble raises."""
        if self.token:
            # Only when a secret is about to travel. Pairing itself sends no
            # token, which is what makes recording the fingerprint possible
            # in the first place.
            self.check_pin()
        ctx = self._context() if self.base.startswith("https") else None
        try:
            with urllib.request.urlopen(req, timeout=15, context=ctx) as resp:
                raw = resp.read().decode("utf-8", "replace")
                return resp.status, _loads(raw), dict(resp.headers)
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            return e.code, _loads(raw), dict(e.headers or {})


def _loads(raw):
    try:
        parsed = json.loads(raw)
        return parsed if isinstance(parsed, dict) else {"result": parsed}
    except ValueError:
        return {"result": raw[:300]}


def pair(hub, name, right, poll_s=3, timeout_s=300, asks=3):
    """Ask to be paired, then wait for the owner to approve it on the PC.

    The name matters more than it looks. A second program asking under a name
    already paired gets that name with a number added -- "Synth Senses 2" --
    while the cluster file still names "Synth Senses", so every write is then
    refused by a program that looks perfectly paired. If that happens the 403
    says so in its `result`, which is why this program prints that text
    verbatim rather than summarising it. Revoke the old pairing first:
    `python -m potluck.hubauth revoke "Synth Senses"` on the hub's PC.
    """
    # Said once, before the first request, because both of the things that
    # went wrong the first time this ran for real were about finding the
    # button rather than about pairing.
    say("")
    say("  Bring the Potluck Desk window to the front, and make sure nothing")
    say("  is covering the top of it. The request appears there as a banner")
    say("  within a couple of seconds; Allow and Deny sit at its RIGHT-HAND")
    say("  END, which is easy to hide behind another window.")
    say("")

    for attempt in range(1, asks + 1):
        status, body, _ = hub.post("/api/pair", {"name": name, "right": right})
        if status != 202:
            say(f"pairing refused: {status} {body.get('result') or body}")
            return None
        pair_id = body.get("pair_id")
        window = body.get("expires_in_s")
        say(f"asked to pair as {name!r} with the {right!r} right"
            + (f" (attempt {attempt} of {asks})" if asks > 1 else ""))
        say(f"waiting for Allow on the hub's PC — pair id {pair_id}, "
            f"{window} s to answer")

        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            time.sleep(poll_s)
            status, body, _ = hub.get(f"/api/pair/{pair_id}")
            state = body.get("state")
            if state == "approved":
                say("approved.")
                return body.get("token")
            if state == "denied":
                # A deliberate no. Asking again would be pestering someone who
                # has already answered.
                say("denied by the owner. Not asking again.")
                return None
            if status == 404:
                break
        # The window lapsed. That is not a failure of anything, it is someone
        # not having got to the button yet, so ask again rather than making
        # them retype the command -- which is what happened the first time.
        if attempt < asks:
            say("that request expired before it was answered; asking again.")
        else:
            say(f"gave up after {asks} requests. Re-run when you are ready to "
                f"click Allow.")
    return None


def write_value(hub, system, name, value, dry_run=False, inbox="phone"):
    """One write.

    -> (ok, hold_seconds, note, keep, throttled). `keep` says whether the
    value is still
    worth writing: a refusal means drop it, but a throttle or a board that did
    not answer means *not now*, and discarding a perfectly good reading there
    leaves the cell showing something older until the next stale-refresh.
    """
    path = f"potluck://{system}/act/{inbox}/{name}"
    if dry_run:
        return True, 0, f"would write {path} = {value}", False, False
    status, body, headers = hub.post("/api/write", {"path": path, "value": value})
    result = body.get("result")
    if status == 200:
        return True, 0, str(result), False, False
    if status == 404:
        # Not an error, and **not a reason to forget the value**. The usual
        # cause is that the cell's owner has not provisioned the path yet,
        # which means it will exist later -- so the reading is kept and
        # retried after the hold rather than dropped.
        #
        # Dropping it was a real bug, and a quiet one. A value that has never
        # been written is not in `written`, so `refresh_due` never re-offers
        # it; a dropped 404 would therefore wait for the reading itself to
        # change before trying again. For `lat` on a phone sitting on a desk
        # that is roughly never, so the coordinates would have stayed NO_DATA
        # after provisioning until someone carried the phone twenty metres.
        #
        # But "usual" is not "only", and printing the inference rather than
        # the hub's own words has already been wrong once: a hub can answer
        # 404 for a resource that IS provisioned, if it predates the fix that
        # taught it to read inbox values. Someone reading "not provisioned
        # yet" would then go looking in the wrong place entirely. So say what
        # the hub said, the same as for a 403, and label the inference as an
        # inference.
        return False, 60, (f"404: {result}" if result
                           else "404, probably not provisioned yet"), True, False
    if status == 429:
        try:
            wait = int(headers.get("Retry-After", "2"))
        except (TypeError, ValueError):
            wait = 2
        # Keep it. The reading is current and correct; the hub is simply
        # asking us to wait, and dropping it would publish staleness.
        return False, wait, f"throttled: {result}", True, True
    if status == 403:
        # Observed live while Potluck re-applied the cell's package: a 403
        # whose body is just "not_found". Mid-reconfiguration the resource is
        # momentarily absent rather than forbidden, so this is the 404 case
        # wearing the wrong status, and dropping it was the same mistake --
        # a value that has never been written successfully is not in
        # `written`, so refresh-before-stale can never re-offer it. That one
        # recovered only because the next sample carried a different reading.
        if "not_found" in str(result or "").lower().replace(" ", "_"):
            return False, 30, (f"403 {result}: reads as not provisioned,"
                               " so keeping it"), True, False
        # Printed exactly as given, at Potluck's request. It is the one
        # message that diagnoses the re-pairing trap, naming both the pairing
        # name and the writers entry, and summarising it would lose precisely
        # the part that matters.
        return False, 30, f"refused (403): {result}", False, False
    # 504 is the owning board not answering in time, which is transient in
    # exactly the way a refusal is not.
    return False, 10, f"{status}: {result}", status == 504, False


# ------------------------------------------------------------------- the loop

def run(stream, pending, budget, send, on_percept=None, tick=0.2,
        sleep=time.sleep):
    """Read percepts, publish numbers. The whole program, minus its plumbing.

    Takes its collaborators rather than building them, so the tests can drive
    it with a fake clock and a fake hub and no sockets at all -- the same
    reason Habituation takes a File on the other side of this project.

    Reading and writing are separated on purpose. Percepts arrive when the
    world does something; writes go out when the budget allows. Draining only
    on arrival looked right and was wrong: a burst of changes followed by
    silence would leave the last values pending forever, and silence is the
    normal case here, because habituation means a still room says nothing for
    minutes at a time. So a drainer runs on its own clock and the reader only
    offers.

    A spooled backlog is not replayed: each percept overwrites the pending
    value, so a flush of two thousand leaves eleven numbers, the newest of each.
    Potluck holds a current reading, not a history.
    """
    stop = threading.Event()

    def drainer():
        while not stop.is_set():
            try:
                _drain(pending, budget, send)
            except Exception as e:            # never let the writer kill the reader
                say(f"  (write loop: {type(e).__name__}: {e})")
            stop.wait(tick)

    worker = threading.Thread(target=drainer, daemon=True)
    worker.start()
    try:
        for line in stream:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if not isinstance(msg, dict):
                continue
            # A reply always carries "type"; a percept never does. The
            # receiver strips the envelope so that holds however it arrived.
            if "type" in msg:
                continue
            pending.offer(values_from(msg))
            if on_percept:
                on_percept(msg)
    finally:
        stop.set()
        worker.join(timeout=2)
    # stdin ended, so nothing more is coming: wait for the budget rather than
    # dropping what is still queued. Only reachable when the input is a file.
    _drain(pending, budget, send, patient=True, sleep=sleep)


def _drain(pending, budget, send, patient=False, sleep=time.sleep,
           max_wait=30.0):
    """Write what is pending, as far as the budget allows.

    `patient` waits for tokens instead of giving up, for the one case where
    we know no more percepts are coming. It is bounded, because a hub that
    keeps refusing must not turn into an infinite loop at exit.
    """
    pending.refresh_due()
    waited = 0.0
    while True:
        item = pending.take()
        if item is None:
            return
        if not budget.ready():
            if not patient or waited >= max_wait:
                return
            sleep(0.1)
            waited += 0.1
            continue
        name, value = item
        budget.spend()
        # Four- or five-tuple: the fifth element says the failure was a
        # throttle, which is the only one that is about this program rather
        # than about one path. Test doubles may still answer with four.
        answer = send(name, value)
        ok, hold, note, keep = answer[:4]
        throttled = answer[4] if len(answer) > 4 else False
        if ok:
            pending.accepted(name, value)
            continue
        if keep:
            # Stand down this path alone and carry on with the rest. Holding
            # the budget here starved the queue: `lat` was unprovisioned, so
            # it 404'd with a 60 s hold, and because a never-written value
            # sorts to the head of the queue it was taken first on every pass
            # -- one absent path reduced all twelve values to a single write
            # a minute. Keeping a 404'd value was right; retrying it from the
            # head of a queue that stopped at the first failure was not.
            pending.hold(name, hold)
        else:
            pending.rejected(name)
        if throttled:
            # A throttle genuinely is program-wide. The hub is telling this
            # writer to slow down, so trying the next path would only earn
            # another 429, and the budget is the right place to carry it.
            if hold:
                budget.hold_off(hold)
            if not patient:
                return


def tail(path, from_start=False, poll_s=0.5):
    """Yield lines from a growing file, forever.

    The receiver already appends every percept to its log, so tailing that is
    the same stream a pipe would carry -- without a pipe. PowerShell does not
    reliably stream between two native commands, which is how this bridge sat
    silent for two hours beside a working receiver, and a file also decouples
    the two: the receiver can be restarted without taking the bridge with it.

    Opens in UTF-8 because a narration or transcript can hold anything, and on
    Windows the default is cp1252.
    """
    # Opened and positioned here rather than inside the generator body, so
    # "from the end" means the end as of this call. A generator defers its
    # body until the first next(), which would leave anything written in
    # between either missed or replayed depending on timing -- and timing is
    # exactly what a tail cannot control.
    handle = io.open(path, "r", encoding="utf-8", errors="replace")
    if not from_start:
        handle.seek(0, os.SEEK_END)

    def lines():
        with handle as f:
            buffered = ""
            while True:
                chunk = f.readline()
                if not chunk:
                    time.sleep(poll_s)
                    continue
                buffered += chunk
                # A reader can catch a line mid-append, so hold a partial one
                # until its newline arrives rather than handing out half a
                # percept for json.loads to choke on.
                if buffered.endswith(chr(10)):
                    whole, buffered = buffered, ""
                    yield whole

    return lines()


def main():
    utf8_streams()
    ap = argparse.ArgumentParser(
        description="Publish percepts into a Potluck cell as eleven numbers.",
        epilog="Reads percepts as JSON lines on stdin, from tools/receiver.py.")
    ap.add_argument("--hub", help="https://<host>:8443 of the Potluck hub")
    ap.add_argument("--system", default="lab", help="the cell id in the path")
    ap.add_argument("--name", default="Synth Senses", help="pairing name")
    ap.add_argument("--token-file", default=".potluck-token.json",
                    help="where the pairing token and certificate pin live")
    ap.add_argument("--pair", action="store_true",
                    help="ask to pair, wait for approval, store the token")
    ap.add_argument("--stale-ms", type=int, default=300000,
                    help="the inbox's stale_ms; values are refreshed at half "
                         "of it (default 300000, about 2.5x the heartbeat)")
    ap.add_argument("--rate", type=float, default=1.2,
                    help="writes per second, under Potluck's ceiling of 2")
    ap.add_argument("--follow", metavar="FILE",
                    help="tail a percept log instead of reading stdin, e.g. "
                         "percepts.jsonl. Avoids a shell pipe entirely, which "
                         "PowerShell does not reliably stream between two "
                         "native commands, and survives the receiver being "
                         "restarted.")
    ap.add_argument("--from-start", action="store_true",
                    help="with --follow, read the file from the beginning "
                         "rather than from its end. Replays history, so it is "
                         "off by default: Potluck holds a current value, not a "
                         "series.")
    ap.add_argument("--dry-run", action="store_true",
                    help="print the writes instead of sending them")
    ap.add_argument("--insecure", action="store_true",
                    help="skip the certificate pin (do not use on a network "
                         "you do not own)")
    ap.add_argument("--manifest", action="store_true",
                    help="print the inbox manifest and exit")
    ap.add_argument("--vocabulary", action="store_true",
                    help="print the published contract as JSON and exit: the "
                         "values and every ordinal list, for a consumer "
                         "to check its own labels against")
    args = ap.parse_args()

    if args.vocabulary:
        # The contract, in a form another program can diff against.
        #
        # A consumer that renders these numbers has to keep a copy of the
        # words, and a copy drifts -- that is how a desktop view came to
        # label ordinal 3 "impact" while this bridge meant "people", from an
        # early proposal of mine that was superseded without anyone noticing
        # the words had moved even though no ordinal had.
        #
        # The fix is not for this repo to reach into a consumer's files and
        # check them. That inverts the dependency, and a test that silently
        # skips when the other project is not checked out is worse than no
        # test. So this publishes instead: the producer states the contract,
        # and each consumer asserts its own copy against this output in its
        # own CI, where failing is useful.
        published = {n for n, *_ in VALUES}
        print(json.dumps({
            "source": "synth-senses tools/potluck_bridge.py",
            "note": "ordinals are indices into these lists; append, never "
                    "insert. A value whose reading is absent is never "
                    "written and reads as NO_DATA.",
            "values": [
                {"name": n, "kind": k, "unit": u} for n, k, u, _r, _e in VALUES
            ],
            # Filtered to what is actually published, so the contract says
            # one thing. thermal stayed listed here after the coordinates
            # took its slot, which the Potluck Desk spotted: its words were
            # still in `ordinals` while its value was gone from `values`.
            "ordinals": {n: words for n, words in VOCABULARIES.items()
                         if n in published},
            "absent": {n: why for n, why in ABSENT_NOTES.items()
                       if n in published},
        }, indent=2))
        return 0

    if args.manifest:
        print(json.dumps({
            "name": "phone", "module": "builtin:inbox",
            "latency_class": 4, "pin": "A",
            "config": {"outputs": manifest_outputs(),
                       "stale_ms": args.stale_ms},
        }, indent=2))
        say("")
        say("and in the cluster file, for each value:")
        for name, _k, _u, _r, _e in VALUES:
            say(f'  "act/phone/{name}": "read_write"')
        say("")
        say(f'with "writers": ["{args.name}"] on each.')
        return 0

    stored = {}
    if os.path.exists(args.token_file):
        try:
            with open(args.token_file, encoding="utf-8") as f:
                stored = json.load(f)
        except (OSError, ValueError) as e:
            say(f"could not read {args.token_file}: {e}")

    hub = None
    if args.hub:
        hub = Hub(args.hub, token=stored.get("token"),
                  fingerprint=stored.get("fingerprint"),
                  insecure=args.insecure)

    if args.pair:
        if not args.hub:
            say("--pair needs --hub")
            return 2
        # Deliberately without the stored token. Pairing carries no
        # credential, which is exactly what lets it read the certificate and
        # record the pin -- and a Hub holding an old token would have its pin
        # checked first and refuse to pair at all when there is no pin yet.
        hub = Hub(args.hub, insecure=args.insecure)
        token = pair(hub, args.name, "write")
        if not token:
            return 1
        stored["token"] = token
        stored["name"] = args.name
        # The pin is the whole reason the token is safe to keep. Recorded
        # here, from the certificate that just approved the pairing, and
        # checked before every authenticated request afterwards.
        if hub.base.startswith("https"):
            try:
                stored["fingerprint"] = hub.peer_fingerprint()
                say(f"pinned the hub's certificate "
                    f"({stored['fingerprint'][:16]}...)")
            except Exception as e:
                say(f"could not read the hub's certificate to pin it: {e}")
                say("writes will refuse to send the token until you pair "
                    "again, or pass --insecure")
        with open(args.token_file, "w", encoding="utf-8") as f:
            json.dump(stored, f, indent=2)
        say(f"token stored in {args.token_file}. Keep it: pairing again under "
            f"a name already taken becomes {args.name + ' 2'!r}, while the "
            f"cluster file still says {args.name!r}, and then every write is "
            f"refused by a program that looks perfectly paired.")
        return 0

    dry = args.dry_run or hub is None
    if dry:
        say("dry run: printing writes instead of sending them"
            + ("" if args.hub else " (no --hub given)"))
    else:
        say(f"publishing to {args.hub} as {stored.get('name', args.name)!r}")
        if not stored.get("token"):
            say("no token: run with --pair first, or writes will be refused")

    pending = Pending(args.stale_ms)
    budget = Budget(per_second=args.rate)
    counts = {"written": 0, "refused": 0}

    def send(name, value):
        ok, hold, note, keep, throttled = write_value(
            hub, args.system, name, value, dry_run=dry)
        counts["written" if ok else "refused"] += 1
        # A kept value is coming back, so say so rather than letting a
        # throttle read like a loss.
        say(("  -> " if ok else "  .. " if keep else "  !! ")
            + f"{name} = {value}   {note}"
            + ("  (will retry)" if keep else ""))
        return ok, hold, note, keep, throttled

    try:
        if args.follow:
            say(f"following {args.follow}"
                + ("" if args.from_start else " from the end"))
            run(tail(args.follow, from_start=args.from_start),
                pending, budget, send)
        else:
            run(sys.stdin, pending, budget, send)
    except KeyboardInterrupt:
        pass
    say(f"\n{counts['written']} written, {counts['refused']} refused")
    return 0


if __name__ == "__main__":
    sys.exit(main())
