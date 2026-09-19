#!/usr/bin/env python3
"""Summarise a percept log into something a person, or a model, can read.

Two jobs, and they turn out to be the same job.

The first is reading a hardware test. docs/ROADMAP.md lists what a phone has
to tell us — whether the camera produced anything, whether habituation
actually gated, what accel_rms looks like while walking, how fast the battery
went, whether the service stalled — and none of that is answerable by
scrolling a JSONL file. This computes it.

The second is handing that to an assistant without handing over the room.
Percepts contain OCR text and, if speech is enabled, transcripts of what
people said near the phone. **Those are redacted by default**, and reported as
counts instead. Pass --raw to include them, deliberately, once you have looked
at what is in there.

    python3 tools/digest.py percepts.jsonl
    python3 tools/digest.py percepts.jsonl --raw     # includes transcripts
    cat percepts.jsonl | python3 tools/digest.py

Stdlib only.
"""
import argparse
import collections
import json
import sys
from datetime import datetime, timezone

REDACTED_NOTE = "(redacted - pass --raw to include)"


def parse_ts(p):
    ts = p.get("ts")
    if not isinstance(ts, str):
        return None
    try:
        return datetime.strptime(ts, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def load(path):
    src = sys.stdin if path in (None, "-") else open(path, encoding="utf-8")
    out, broken = [], 0
    try:
        for line in src:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                broken += 1
    finally:
        if src is not sys.stdin:
            src.close()
    return out, broken


def span(values):
    vals = [v for v in values if isinstance(v, (int, float))]
    if not vals:
        return None
    return min(vals), max(vals), sum(vals) / len(vals)


def fmt_span(s, unit="", places=2):
    if s is None:
        return "never reported"
    lo, hi, mean = s
    return f"{lo:.{places}f} to {hi:.{places}f}{unit}, mean {mean:.{places}f}"


def section(title):
    print(f"\n{title}")
    print("-" * len(title))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("log", nargs="?", default="percepts.jsonl",
                    help="JSONL file, or - for stdin (default: percepts.jsonl)")
    ap.add_argument("--raw", action="store_true",
                    help="include OCR text and speech transcripts")
    ap.add_argument("--top", type=int, default=8, help="how many of each list to show")
    args = ap.parse_args()

    percepts, broken = load(args.log)
    if not percepts:
        print("no percepts found")
        return 1

    times = [t for t in (parse_ts(p) for p in percepts) if t]
    print(f"percepts   {len(percepts)}" + (f"   ({broken} unparseable lines)" if broken else ""))
    if times:
        lo, hi = min(times), max(times)
        mins = (hi - lo).total_seconds() / 60
        print(f"span       {lo:%Y-%m-%d %H:%M:%S} to {hi:%H:%M:%S} UTC   ({mins:.1f} min)")
        if mins > 0:
            print(f"rate       {len(percepts) / mins:.1f} percepts/min delivered")
    devices = collections.Counter(p.get("device_id") for p in percepts)
    print(f"devices    {', '.join(f'{d} ({n})' for d, n in devices.most_common())}")

    # ---- did the gate behave? ----
    section("attention")
    trig = collections.Counter(p.get("trigger") for p in percepts)
    print("triggers   " + ", ".join(f"{k}: {v}" for k, v in trig.most_common()))
    sal = span([(p.get("attention") or {}).get("salience") for p in percepts])
    print(f"salience   {fmt_span(sal)}")
    floors = collections.Counter(
        (p.get("attention") or {}).get("floor_reason") for p in percepts
        if (p.get("attention") or {}).get("floor_reason"))
    if floors:
        print("floors     " + ", ".join(f"{k}: {v}" for k, v in floors.most_common()))
    known = [(p.get("attention") or {}).get("known_tokens") for p in percepts]
    ks = span(known)
    if ks:
        print(f"vocabulary {int(ks[0])} to {int(ks[1])} known tokens "
              f"({'growing' if known[-1] and known[0] and known[-1] > known[0] else 'flat'})")
    if trig.get("heartbeat") and trig.get("salient"):
        print("note       both salient and heartbeat present, so the gate is doing something")
    elif not trig.get("heartbeat"):
        print("note       no heartbeats: nothing ever went quiet, which is worth a look")

    # ---- which senses actually produced anything ----
    section("senses present")
    for block in ("vision", "hearing", "radio", "gps"):
        n = sum(1 for p in percepts if p.get(block))
        state = "never" if n == 0 else f"{n}/{len(percepts)}"
        print(f"{block:10s} {state}")

    vis = [p["vision"] for p in percepts if p.get("vision")]
    if vis:
        section("vision")
        labels = collections.Counter(
            l.get("name") for v in vis for l in (v.get("labels") or []) if l.get("name"))
        objs = collections.Counter(
            o.get("name") for v in vis for o in (v.get("objects") or []) if o.get("name"))
        print("labels     " + (", ".join(f"{k} ({n})" for k, n in labels.most_common(args.top))
                               or "none"))
        print("objects    " + (", ".join(f"{k} ({n})" for k, n in objs.most_common(args.top))
                               or "none"))
        print(f"brightness {fmt_span(span([v.get('brightness') for v in vis]))}")
        texts = [v.get("text") for v in vis if v.get("text")]
        if args.raw:
            print(f"text read  {len(texts)} frames")
            for t in texts[:args.top]:
                print(f"           {t!r}")
        else:
            print(f"text read  {len(texts)} frames {REDACTED_NOTE}")

    hear = [p["hearing"] for p in percepts if p.get("hearing")]
    if hear:
        section("hearing")
        print(f"level      {fmt_span(span([h.get('level_db') for h in hear]), ' dBFS', 1)}")
        print(f"peak       {fmt_span(span([h.get('peak_db') for h in hear]), ' dBFS', 1)}")
        events = collections.Counter(
            e.get("name") for h in hear for e in (h.get("events") or []) if e.get("name"))
        print("events     " + (", ".join(f"{k} ({n})" for k, n in events.most_common(args.top))
                               or "none - no yamnet.tflite, or silence"))
        trs = [h.get("transcript") for h in hear if h.get("transcript")]
        if args.raw:
            print(f"transcripts {len(trs)}")
            for t in trs[:args.top]:
                print(f"           {t!r}")
        else:
            print(f"transcripts {len(trs)} {REDACTED_NOTE}")

    rad = [p["radio"] for p in percepts if p.get("radio")]
    if rad:
        section("radio and place")
        print(f"ble count  {fmt_span(span([r.get('ble_count') for r in rad]), '', 1)}")
        print(f"wifi count {fmt_span(span([r.get('wifi_count') for r in rad]), '', 1)}")
        places = collections.Counter(r.get("place_id") for r in rad if r.get("place_id"))
        print(f"places     {len(places)} distinct: "
              + (", ".join(f"{k} ({n})" for k, n in places.most_common(args.top)) or "none"))
        new = sum(1 for r in rad if r.get("place_is_new"))
        print(f"new places {new}"
              + ("   <-- fragmenting, the learning window may need a look"
                 if new > max(2, len(rad) * 0.2) else ""))

    # ---- the numbers the thresholds need ----
    section("body (the numbers BodySensor's thresholds were guessed from)")
    bodies = [p.get("body") or {} for p in percepts]
    motion = collections.Counter(b.get("motion") for b in bodies if b.get("motion"))
    posture = collections.Counter(b.get("posture") for b in bodies if b.get("posture"))
    print("motion     " + ", ".join(f"{k}: {v}" for k, v in motion.most_common()))
    print("posture    " + ", ".join(f"{k}: {v}" for k, v in posture.most_common()))
    for field, unit, places in (("accel_rms", "", 3), ("gyro_rms", "", 3),
                                ("lux", " lux", 0), ("magnetic_anomaly", " uT", 1),
                                ("pressure_delta_per_min", " hPa/min", 3)):
        print(f"{field:10s} {fmt_span(span([b.get(field) for b in bodies]), unit, places)}")
    # per-motion breakdown is the actually useful bit for retuning
    by_motion = collections.defaultdict(list)
    for b in bodies:
        if b.get("motion") and isinstance(b.get("accel_rms"), (int, float)):
            by_motion[b["motion"]].append(b["accel_rms"])
    if len(by_motion) > 1:
        print("accel_rms per motion state:")
        for m, vals in sorted(by_motion.items()):
            print(f"           {m:10s} {min(vals):.3f} to {max(vals):.3f}, "
                  f"mean {sum(vals)/len(vals):.3f}   (n={len(vals)})")

    # ---- did it survive, and at what cost ----
    section("self")
    selves = [p.get("self") or {} for p in percepts]
    batt = [s.get("battery") for s in selves if isinstance(s.get("battery"), (int, float))]
    if batt and times and len(batt) > 1:
        drop = (batt[0] - batt[-1]) * 100
        hours = (max(times) - min(times)).total_seconds() / 3600
        print(f"battery    {batt[0]*100:.0f}% to {batt[-1]*100:.0f}%  ({drop:+.1f} points)")
        if hours > 0.05 and drop > 0:
            print(f"           ~{drop/hours:.1f} points/hour -> roughly "
                  f"{100/(drop/hours):.1f} h from full")
    print("thermal    " + ", ".join(
        f"{k}: {v}" for k, v in collections.Counter(
            s.get("thermal") for s in selves if s.get("thermal")).most_common()))
    print(f"batt temp  {fmt_span(span([s.get('battery_temp_c') for s in selves]), ' C', 1)}")
    print(f"headroom   {fmt_span(span([s.get('thermal_headroom') for s in selves]))}")
    nets = collections.Counter(s.get("net") for s in selves if s.get("net"))
    print("network    " + ", ".join(f"{k}: {v}" for k, v in nets.most_common()))

    # ---- gaps mean the service stalled or was killed ----
    if len(times) > 2:
        section("continuity")
        ordered = sorted(times)
        gaps = [(ordered[i + 1] - ordered[i]).total_seconds()
                for i in range(len(ordered) - 1)]
        gs = span(gaps)
        print(f"gap        {fmt_span(gs, ' s', 1)}")
        big = [g for g in gaps if g > 180]
        if big:
            print(f"stalls     {len(big)} gaps over 3 min, longest {max(big)/60:.1f} min"
                  "   <-- the service may have been killed")
        else:
            print("stalls     none over 3 min")

    # ---- what never appeared at all ----
    section("never reported")
    missing = []
    for field in ("lux", "pressure_hpa", "magnetic_ut", "heading_deg", "steps",
                  "covered", "gyro_rms"):
        if not any(isinstance((p.get("body") or {}).get(field), (int, float))
                   or isinstance((p.get("body") or {}).get(field), bool) for p in percepts):
            missing.append(f"body.{field}")
    for field in ("battery_temp_c", "current_ma", "voltage_v", "thermal_headroom"):
        if not any(isinstance((p.get("self") or {}).get(field), (int, float)) for p in percepts):
            missing.append(f"self.{field}")
    print(", ".join(missing) if missing else "everything reported at least once")
    print("(null means the hardware or permission is absent, which is correct behaviour,\n"
          " not a bug, but an unexpected entry here is worth chasing)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
