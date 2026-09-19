#!/usr/bin/env python3
"""Tests for digest.py, and mostly for one property.

The digest exists so a percept log can be handed to a person or an assistant.
That makes its redaction a privacy control, not a formatting preference: OCR
text is whatever the camera could read, which on a desk means documents and
screens, and a transcript is what someone in the room said. If either leaks
into the default output, it leaks into wherever that output gets pasted.

So the load-bearing assertion here is negative: given a log containing a
distinctive string, that string must not appear unless --raw was passed.

Run: python3 tools/test_digest.py
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DIGEST = os.path.join(HERE, "digest.py")

SECRET_TEXT = "ACCOUNT-90210-SORTCODE"
SECRET_SPEECH = "the spare key is under the mat"

failures = []


def check(label, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{'  ' + detail if detail else ''}")
    if not ok:
        failures.append(label)


def percept(seq, ts, **over):
    p = {
        "schema": 2, "device_id": "phone01", "seq": seq, "ts": ts, "trigger": "salient",
        "attention": {"salience": 0.5, "novel": [], "known_tokens": 10 + seq,
                      "dishabituated": False, "floor_reason": None},
        "vision": {"lens": "back", "brightness": 0.4,
                   "labels": [{"name": "Room", "conf": 0.9}], "objects": [], "text": None},
        "hearing": {"level_db": -40.0, "peak_db": -20.0,
                    "events": [{"name": "Speech", "conf": 0.8}], "transcript": None},
        "radio": {"ble_count": 4, "ble_named": [], "ble_strongest_rssi": -60,
                  "wifi_count": 9, "wifi_connected": "MyHouse-5G", "wifi_strongest_rssi": -45,
                  "cell": None, "place_id": "p1", "place_name": None,
                  "place_similarity": 0.9, "place_is_new": False},
        "body": {"motion": "still", "posture": "face_up", "accel_rms": 0.03, "gyro_rms": 0.01,
                 "heading_deg": 90, "steps": 100, "steps_delta": 0, "lux": 300.0,
                 "covered": False, "magnetic_ut": 47.0, "magnetic_anomaly": 2.0,
                 "pressure_hpa": 1013.0, "pressure_delta_per_min": 0.01},
        "self": {"battery": 0.9, "charging": False, "battery_temp_c": 31.0, "current_ma": -400.0,
                 "voltage_v": 4.0, "thermal": "NONE", "thermal_headroom": 0.4,
                 "mem_free_pct": 0.5, "mem_low": False, "storage_free_pct": 0.6,
                 "screen_on": False, "net": "wifi", "uptime_s": 100},
        "tempo": {"local_time": "20:00", "tz_offset_min": 0, "day_of_week": "Saturday",
                  "part_of_day": "evening", "solar_elevation_deg": -3.0, "is_daylight": False,
                  "minutes_to_sunset": None, "minutes_since_sunrise": 700, "day_length_min": 800},
        "gps": None, "narration": "A room, quietly.",
    }
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(p.get(k), dict):
            p[k] = {**p[k], **v}
        else:
            p[k] = v
    return p


def write_log(rows, path):
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")


def run(path, *extra):
    r = subprocess.run([sys.executable, DIGEST, path, *extra],
                       capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def main():
    tmp = tempfile.mkdtemp()
    log = os.path.join(tmp, "percepts.jsonl")

    rows = []
    for i in range(12):
        ts = f"2026-09-19T18:{i:02d}:00.000Z"
        over = {}
        if i == 3:
            over["vision"] = {"text": SECRET_TEXT}
        if i == 5:
            over["hearing"] = {"transcript": SECRET_SPEECH}
        if i in (8, 9, 10):
            over["body"] = {"motion": "walking", "accel_rms": 1.9, "gyro_rms": 0.6}
        rows.append(percept(i, ts, **over))
    # a six minute hole, which is what a killed service looks like
    rows.append(percept(12, "2026-09-19T18:17:00.000Z"))
    write_log(rows, log)

    print("redaction (the reason this tool exists)")
    code, out = run(log)
    check("runs", code == 0, f"exit {code}")
    check("OCR text does not leak by default", SECRET_TEXT not in out)
    check("a transcript does not leak by default", SECRET_SPEECH not in out)
    check("but it still says how many there were", "text read  1 frames" in out)
    check("and how many transcripts", "transcripts 1" in out)
    check("and says how to get them", "--raw" in out)

    code, raw = run(log, "--raw")
    check("--raw includes the OCR text", SECRET_TEXT in raw, "")
    check("--raw includes the transcript", SECRET_SPEECH in raw, "")

    print("\nthe numbers a hardware test is actually run for")
    check("splits accel_rms by motion state, which is what retunes the thresholds",
          "accel_rms per motion state" in out and "walking" in out)
    check("counts triggers", "triggers" in out and "salient" in out)
    check("reports battery movement", "battery" in out)
    check("notices a stall", "stalls     1 gaps over 3 min" in out, "")
    check("reports which senses ever produced anything", "senses present" in out)

    print("\ndegradation and bad input")
    bare = os.path.join(tmp, "bare.jsonl")
    write_log([{"schema": 2, "device_id": "d", "seq": 1,
                "ts": "2026-09-19T18:00:00.000Z", "trigger": "first",
                "attention": None, "vision": None, "hearing": None, "radio": None,
                "body": {"motion": "still", "posture": "unknown", "accel_rms": 0.0,
                         "gyro_rms": 0.0, "heading_deg": None, "steps": None,
                         "steps_delta": None, "lux": None, "covered": None,
                         "magnetic_ut": None, "magnetic_anomaly": None,
                         "pressure_hpa": None, "pressure_delta_per_min": None},
                "self": {"battery": 0.5, "charging": False, "thermal": "UNKNOWN"},
                "tempo": {}, "gps": None, "narration": "x"}], bare)
    code, out2 = run(bare)
    check("a phone with no camera, mic or radio does not crash it", code == 0, f"exit {code}")
    check("and it says which sensors never reported", "never reported" in out2)
    check("naming the absent ones", "body.lux" in out2, "")

    corrupt = os.path.join(tmp, "corrupt.jsonl")
    with open(corrupt, "w", encoding="utf-8") as f:
        f.write(json.dumps(percept(1, "2026-09-19T18:00:00.000Z")) + "\n")
        f.write("this is not json\n\n")
        f.write(json.dumps(percept(2, "2026-09-19T18:00:05.000Z")) + "\n")
    code, out3 = run(corrupt)
    check("a corrupt line is counted, not fatal", code == 0 and "1 unparseable" in out3, "")

    empty = os.path.join(tmp, "empty.jsonl")
    open(empty, "w").close()
    code, out4 = run(empty)
    check("an empty log says so and exits non-zero",
          code == 1 and "no percepts" in out4, f"exit {code}")

    print(f"\n{len(failures)} failure(s)")
    for f in failures:
        print("  -", f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
