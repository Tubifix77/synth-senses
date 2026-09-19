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

print("\ncross-document consistency")
check("INTEGRATION defers to PROTOCOL as normative",
      "normative" in integration and "PROTOCOL.md" in integration)
for doc, label in ((protocol, "PROTOCOL.md"), (integration, "INTEGRATION.md")):
    check(f"{label} agrees the schema is {schema_src.group(1)}",
          f'"schema": {schema_src.group(1)}' in doc)

print("\nno promises the code does not keep")
# the spec must keep saying the return path is not reachable from code, for as
# long as that is true. If someone wires it up, this fails and the spec gets
# corrected rather than quietly becoming pessimistic.
console_gated = "sys.stdin.isatty()" in read("tools", "receiver.py")
claims_unavailable = "not reachable from code yet" in integration
check("the return path is still human-only, and the spec still says so",
      console_gated and claims_unavailable,
      f"gated={console_gated} documented={claims_unavailable}")

print(f"\n{len(failures)} failure(s)")
for f in failures:
    print("  -", f)
sys.exit(1 if failures else 0)
