#!/usr/bin/env python3
"""Check that the documented facts match the source.

docs/INTEGRATION.md is written for someone building the consuming side, who
will code against it without reading the Kotlin. That makes a stale number in
it worse than no document: it is confidently wrong, and the person it misleads
has no way to notice.

So the handful of facts that can be checked mechanically are checked. This
does not verify prose, and it is not a substitute for reading the thing. It
catches the specific failure where the code moves and the spec does not.

Run: python3 tools/test_docs.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def utf8_streams():
    """Make stdout and stderr UTF-8, whatever the platform thinks.

    A narration, an OCR line or a transcript can hold any character at all.
    On Windows both a console and a pipe default to cp1252, so the first
    character outside it raises UnicodeEncodeError -- on a word someone
    happened to say, which is the worst possible trigger and never reproduces
    in CI, where Linux is UTF-8 already.
    """
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


integration = read("docs", "INTEGRATION.md")
protocol = read("docs", "PROTOCOL.md")
link_kt = read("app", "src", "main", "java", "net", "synthsenses", "senses", "Link.kt")
prefs_kt = read("app", "src", "main", "java", "net", "synthsenses", "senses", "Prefs.kt")
percept_kt = read("app", "src", "main", "java", "net", "synthsenses", "senses", "Percept.kt")
service_kt = read("app", "src", "main", "java", "net", "synthsenses", "senses", "SenseService.kt")
radio_kt = read("app", "src", "main", "java", "net", "synthsenses", "senses", "RadioSensor.kt")

print("the command vocabulary")
# the real list, from the constants Commands.NAMES is built out of
declared = set(re.findall(r'const val [A-Z_]+ = "([a-z_]+)"', link_kt))
# Scope to the command table. A bare row regex also swallows the field table
# above it, which is how this first reported `narration` as an invented
# command -- the check was wrong, not the document.
after = integration.split("The vocabulary, so you can design an intent model", 1)
table = after[1].split("\n## ", 1)[0] if len(after) > 1 else ""
documented = set(re.findall(r"^\| `([a-z_]+)` \|", table, re.M))
check("every command in the source is documented",
      declared <= documented, f"missing: {sorted(declared - documented)}")
check("no command is documented that does not exist",
      documented <= declared, f"invented: {sorted(documented - declared)}")
count_claim = re.search(r"accepts (\w+) commands over the websocket", integration)
words = {"eleven": 11, "ten": 10, "twelve": 12, "nine": 9}
if count_claim:
    claimed = words.get(count_claim.group(1))
    check(f"the stated count matches ({count_claim.group(1)})",
          claimed == len(declared), f"source has {len(declared)}")
else:
    check("the stated count is findable", False, "phrase not found")

print("\ntiming defaults")
for label, pattern, doc_pattern in (
    ("sample interval", r'getLong\("interval_ms", ([0-9_]+)L\)', r"sample interval \| (\d+) s"),
    ("heartbeat", r'getLong\("heartbeat_ms", ([0-9_]+)L\)', r"heartbeat \| (\d+) s"),
):
    src = re.search(pattern, prefs_kt)
    doc = re.search(doc_pattern, integration)
    if not src or not doc:
        check(label, False, f"src={bool(src)} doc={bool(doc)}")
        continue
    src_s = int(src.group(1).replace("_", "")) // 1000
    check(f"{label}: {src_s} s", src_s == int(doc.group(1)),
          f"doc says {doc.group(1)} s")

thr_src = re.search(r'getFloat\("threshold", ([0-9.]+)f\)', prefs_kt)
thr_doc = re.search(r"salience threshold \| ([0-9.]+)", integration)
check("salience threshold", thr_src and thr_doc and
      float(thr_src.group(1)) == float(thr_doc.group(1)),
      f"src={thr_src.group(1) if thr_src else '?'} doc={thr_doc.group(1) if thr_doc else '?'}")

max_rate = re.search(r"at most (\d+) percepts per minute", integration)
if max_rate and thr_src:
    interval = int(re.search(r'getLong\("interval_ms", ([0-9_]+)L\)',
                             prefs_kt).group(1).replace("_", "")) / 1000
    check("the derived ceiling is arithmetic", int(max_rate.group(1)) == int(60 / interval),
          f"doc says {max_rate.group(1)}, 60/{interval:g} = {60/interval:g}")

print("\nschema and identifiers")
schema_src = re.search(r"const val SCHEMA_VERSION = (\d+)", percept_kt)
check("schema version matches Percept.kt",
      schema_src and f'"schema": {schema_src.group(1)}' in integration
      and f"stable at version {schema_src.group(1)}" in integration,
      f"source says {schema_src.group(1) if schema_src else '?'}")
check("the hash is described as it is implemented",
      "0xcbf29ce484222325uL" in radio_kt and "take(10)" in radio_kt
      and "FNV-1a" in integration and "10 hex" in integration)

print("\ntrigger values")
# first/salient/heartbeat are decided in Habituation.evaluate, not in the
# service. Looking in the wrong file made this quietly check almost nothing.
habituation_kt = read("app", "src", "main", "java", "net", "synthsenses",
                      "senses", "Habituation.kt")
triggers_src = set(re.findall(r'-> "(first|salient|heartbeat)"', habituation_kt))
triggers_src |= set(re.findall(r'"(requested)"', service_kt))
check("all four trigger values were found in the source",
      triggers_src == {"first", "salient", "heartbeat", "requested"},
      f"found {sorted(triggers_src)}")
for t in sorted(triggers_src):
    check(f"`{t}` is documented", f"`{t}`" in integration)

print("\nthe closed vocabularies, and their order")
# Potluck, and anything else that cannot carry strings, sends the INDEX into
# these lists. That makes the order a wire contract which no JSON ever shows,
# so a reordering is invisible everywhere except here and in VocabularyTest.
vocab_kt = read("app", "src", "main", "java", "net", "synthsenses",
                "senses", "Vocabulary.kt")
consts = dict(re.findall(r'const val ([A-Z_]+) = "([^"]+)"', vocab_kt))


def vocab(name):
    """The ordered list behind `val NAME = listOf(...)`, constants resolved."""
    m = re.search(r"val %s = listOf\(([^)]*)\)" % name, vocab_kt, re.S)
    if not m:
        return []
    out = []
    for tok in re.split(r",\s*", m.group(1).strip()):
        tok = tok.strip()
        if tok:
            out.append(consts.get(tok, tok.strip('"')))
    return out


def documented(field):
    row = re.search(r"^\| `%s` \| (.+)$" % re.escape(field), protocol, re.M)
    return re.findall(r"`([A-Za-z_]+)`", row.group(1)) if row else None


# THERMAL carries UNKNOWN on the wire but deliberately keeps it out of the
# ladder, so the document legitimately lists one more value than the list has.
for field, name, extra in (("body.motion", "MOTION", []),
                           ("body.posture", "POSTURE", []),
                           ("self.thermal", "THERMAL", ["THERMAL_UNKNOWN"])):
    src = vocab(name)
    want = src + [consts[e] for e in extra]
    doc = documented(field)
    check("%s was parsed out of Vocabulary.kt" % name, bool(src), str(src))
    check("PROTOCOL.md lists `%s` exactly as the source defines it" % field,
          doc == want, "doc=%s src=%s" % (doc, want))

# The roadmap states ordinal ranges to a consumer that will hard-code them, so
# an off-by-one there is a silently mislabelled value on someone else's board.
roadmap = read("docs", "ROADMAP.md")
# Only the vocabularies that actually cross as ordinals. THERMAL's order is
# still a severity ladder and VocabularyTest still pins it, but nothing
# publishes it as a number any more -- it was swapped out of the Potluck set
# for the exact coordinates, so the roadmap has no ordinal range to state.
for field, name in (("body.motion", "MOTION"),
                    ("body.posture", "POSTURE")):
    m = re.search(r"`%s`, 0 ([a-z_]+) . (\d+) ([a-z_]+)" % re.escape(field), roadmap)
    src = vocab(name)
    if not m or not src:
        check("the roadmap states the %s ordinal range" % name, False)
        continue
    first, last_i, last = m.group(1).strip().replace(" ", "_"), int(m.group(2)), m.group(3)
    check("the roadmap's %s range matches the source" % name,
          first.lower() == src[0].lower()
          and last_i == len(src) - 1
          and last.lower() == src[-1].lower(),
          "doc=0 %s .. %d %s  src=0 %s .. %d %s"
          % (first, last_i, last, src[0], len(src) - 1, src[-1]))

print("\ncross-document consistency")
check("INTEGRATION defers to PROTOCOL as normative",
      "normative" in integration and "PROTOCOL.md" in integration)
for doc, label in ((protocol, "PROTOCOL.md"), (integration, "INTEGRATION.md")):
    check(f"{label} agrees the schema is {schema_src.group(1)}",
          f'"schema": {schema_src.group(1)}' in doc)

print("\nthe return path, which now exists")
# This check used to assert the opposite -- that the path was human-only and
# the spec still said so -- precisely so that wiring it up would fail here and
# force the document to be corrected rather than quietly becoming pessimistic.
# It did its job; this is the other side of it.
receiver_py = read("tools", "receiver.py")
reads_stdin = "def commands_from_stdin(" in receiver_py
emits_replies = "def emit_reply(" in receiver_py
check("the receiver reads commands from stdin", reads_stdin)
check("and sends the phone's answers back out", emits_replies)
check("the spec no longer claims it is unreachable",
      "not reachable from code yet" not in integration
      and "does not work from code yet" not in integration)
check("and documents the rule for telling a reply from a percept",
      "a reply always carries" in integration and "never does" in integration)

# The one thing a consumer cannot get wrong without silent damage: replies and
# percepts share a stream. Both documents have to state the discriminator.
for doc, label in ((protocol, "PROTOCOL.md"), (integration, "INTEGRATION.md")):
    check(f"{label} says a percept carries no type",
          "type" in doc and ("never" in doc or "stripped" in doc))

# Every command the receiver will forward must be one the app actually has.
known = set(re.findall(r'KNOWN_COMMANDS = frozenset\(\((.*?)\)\)',
                       receiver_py, re.S))
forwarded = set(re.findall(r'"([a-z_]+)"', " ".join(known)))
check("the receiver's whitelist matches the app's command constants",
      forwarded == declared, f"receiver={sorted(forwarded)} app={sorted(declared)}")

print("\ndiscovery, which has three places to go stale")
# The beacon is the one number living in a Kotlin file, a Python file and a
# document at once, and two of the three fail silently: a wrong port in the
# app means Find never finds anything, and a wrong port in PROTOCOL.md means
# whoever writes the next receiver is told to shout down an empty street.
endpoint_kt = read("app", "src", "main", "java", "net", "synthsenses",
                   "senses", "Endpoint.kt")


def kt_const(name):
    m = re.search(rf'const val {name}\s*=\s*"?([^"\s]+)"?', endpoint_kt)
    return m.group(1) if m else None


beacon_port = kt_const("BEACON_PORT")
check("Endpoint.kt names a beacon port", beacon_port is not None)
check("receiver.py announces on the same port",
      f"BEACON_PORT = {beacon_port}" in receiver_py, f"Endpoint.kt={beacon_port}")
check("PROTOCOL.md documents that port",
      beacon_port is not None and beacon_port in protocol, f"expected {beacon_port}")
check("and the magic key the app checks for",
      kt_const("BEACON_MAGIC") in protocol, str(kt_const("BEACON_MAGIC")))
check("the beacon is documented as carrying no percepts",
      "no percept, command or reply ever travels on it" in protocol)
check("the receiver can be told not to broadcast",
      '"--no-beacon"' in receiver_py and "--no-beacon" in protocol)

print(f"\n{len(failures)} failure(s)")
for f in failures:
    print("  -", f)
sys.exit(1 if failures else 0)
