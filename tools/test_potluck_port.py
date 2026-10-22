#!/usr/bin/env python3
"""The port that serves the phone's senses to a companion: what it answers, what
it withholds, and above all that location cannot reach it."""
import json
import os
import sys
import threading
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import potluck_port as pp  # noqa: E402
from http.server import ThreadingHTTPServer  # noqa: E402

failures = []


def check(label, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{'  ' + detail if detail else ''}")
    if not ok:
        failures.append(label)


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def res(name, value, quality="GOOD", age_ms=4000):
    return {"name": "act/phone/" + name, "path": "potluck://lab/act/phone/" + name,
            "value": value, "quality": quality, "age_ms": age_ms}


# A model shaped like the real hub's: twelve phone values, plus a board's.
MODEL = {"resources": [
    res("salience", 0.41), res("motion", 0), res("posture", 0), res("steps", 375),
    res("sound", 0, age_ms=2000), res("loudness", -51.2, age_ms=2000),
    res("place", 2, age_ms=61000), res("ble_count", 9, age_ms=61000),
    res("battery", 0.81), res("battery_temp", 34.0),
    res("lat", 56.0922761, age_ms=1000), res("lon", 10.1184221, age_ms=1000),
    {"name": "act/board_b/temperature", "value": 51.5, "quality": "GOOD", "age_ms": 10},
]}


def snap(model=MODEL):
    clk = Clock()
    s = pp.Snapshot(now=clk)
    s.update(model)
    return s, clk


print("\nonly the phone's values are taken from the model")
v = pp.phone_values(MODEL)
check("twelve phone values", len(v) == 12, str(sorted(v)))
check("a board's resource is not one of them", "temperature" not in v)

print("\neach agreed path, as the companion will read it")
s, clk = snap()
st, body = s.answer("/phone/body")
check("body answers 200", st == 200, str(st))
check("ordinals come back as words", body.get("motion") == "still" and body.get("posture") == "face_up",
      str(body))
check("steps is a number", body.get("steps") == 375)
st, body = s.answer("/phone/hearing")
check("hearing: sound in words, loudness in dB",
      st == 200 and body.get("sound") == "quiet" and body.get("loudness_db") == -51.2, str(body))
st, body = s.answer("/phone/place")
check("place and ble_count", st == 200 and body.get("place") == 2 and body.get("ble_count") == 9, str(body))
check("age_s is the oldest reading in the answer", body.get("age_s") == 61, str(body.get("age_s")))
st, body = s.answer("/phone/self")
check("battery and its temperature", st == 200 and body.get("battery") == 0.81
      and body.get("battery_temp_c") == 34.0, str(body))
st, body = s.answer("/phone/attention")
check("salience", st == 200 and body.get("salience") == 0.41, str(body))
check("every 200 carries age_s",
      all("age_s" in s.answer(p)[1] for p in pp.ROUTES if s.answer(p)[0] == 200))

print("\nthe age travels: time since the port last read the hub is added")
clk.t += 9
check("nine seconds later, nine seconds older", s.answer("/phone/attention")[1].get("age_s") == 13,
      str(s.answer("/phone/attention")[1]))

print("\nlocation cannot reach the companion")
check("exactly the five agreed paths, and no other",
      sorted(pp.ROUTES) == ["/phone/attention", "/phone/body", "/phone/hearing", "/phone/place", "/phone/self"],
      str(sorted(pp.ROUTES)))
s, clk = snap()
check("/phone/location is not a sense this port serves", s.answer("/phone/location")[0] == 404)
leaked = []
for p in pp.ROUTES:
    _st, b = s.answer(p)
    text = json.dumps(b)
    if "56.09" in text or "10.118" in text or "lat" in b or "lon" in b:
        leaked.append(p)
check("no path carries lat or lon, though the model holds both", not leaked, str(leaked))
check("nothing in the routing table even names them",
      not any(vn in ("lat", "lon") for f in pp.ROUTES.values() for _o, vn in f))

print("\nabsence stays absence, never zero")
m = {"resources": [res("motion", 0), res("posture", 0, quality="NO_DATA"), res("steps", 375)]}
s, _ = snap(m)
st, body = s.answer("/phone/body")
check("NO_DATA is left out", st == 200 and "posture" not in body, str(body))
m = {"resources": [res("motion", 0, quality="STALE", age_ms=400000)]}
st, body = snap(m)[0].answer("/phone/body")
check("STALE is withheld: the phone stopped reporting it, so it is not now",
      st == 503, str((st, body)))
m = {"resources": [res("motion", 0), res("posture", 0, quality="STALE")]}
st, body = snap(m)[0].answer("/phone/body")
check("and a STALE field drops out while a GOOD one stays",
      st == 200 and "posture" not in body and body.get("motion") == "still", str(body))
m = {"resources": [res("motion", 9), res("posture", 0)]}
st, body = snap(m)[0].answer("/phone/body")
check("an ordinal naming no word is left out", "motion" not in body and body.get("posture") == "face_up",
      str(body))
m = {"resources": [res("motion", 0.5)]}
check("a fractional ordinal is not rounded into a word", snap(m)[0].answer("/phone/body")[0] == 503)
m = {"resources": [res("steps", True)]}
check("a boolean is not a number", snap(m)[0].answer("/phone/body")[0] == 503)
m = {"resources": [dict(res("steps", 375), age_ms=None)]}
check("a reading without an age is not vouched for", snap(m)[0].answer("/phone/body")[0] == 503)
m = {"resources": [res("lat", 56.09)]}
st, body = snap(m)[0].answer("/phone/body")
check("nothing for a path is 503, an honest absence", st == 503, str((st, body)))
check("and is still a JSON object", isinstance(body, dict))

m = {"resources": [res("loudness", -47.799999)]}
check("32-bit float noise is trimmed, not passed into permanent memory",
      snap(m)[0].answer("/phone/hearing")[1].get("loudness_db") == -47.8,
      str(snap(m)[0].answer("/phone/hearing")[1]))

print("\nthe hub being unreachable is 502, the blind spot")
clk = Clock()
s = pp.Snapshot(now=clk)
check("before the first read of the hub", s.answer("/phone/body")[0] == 502)
s.update(MODEL)
clk.t += pp.LINK_DOWN_S + 1
check("after the link has been down a while", s.answer("/phone/body")[0] == 502)
check("an unknown path is still 404, not 502", s.answer("/phone/nothing")[0] == 404)

print("\nover real HTTP, on loopback")
check("bound to loopback, by construction", pp.BIND == "127.0.0.1")
s, _ = snap()
srv = ThreadingHTTPServer((pp.BIND, 0), pp.make_handler(s))
port = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()
try:
    with urllib.request.urlopen("http://127.0.0.1:%d/phone/body" % port, timeout=5) as r:
        got = json.loads(r.read().decode("utf-8"))
        check("GET answers JSON", r.status == 200 and got.get("motion") == "still", str(got))
        check("with a JSON content type", "application/json" in r.headers.get("Content-Type", ""))
        check("well under the companion's 64 KB", len(json.dumps(got)) < 64 * 1024)
    try:
        urllib.request.urlopen(urllib.request.Request(
            "http://127.0.0.1:%d/phone/body" % port, data=b"{}", method="POST"), timeout=5)
        check("POST is refused", False, "it was accepted")
    except urllib.error.HTTPError as e:
        check("POST is refused: senses, never actuators", e.code == 405, str(e.code))
    try:
        urllib.request.urlopen("http://127.0.0.1:%d/phone/location" % port, timeout=5)
        check("location over HTTP", False, "it answered")
    except urllib.error.HTTPError as e:
        check("location over HTTP is 404", e.code == 404, str(e.code))
finally:
    srv.shutdown()
    srv.server_close()

print("\n%d failure(s)" % len(failures))
for f in failures:
    print("  - " + f)
sys.exit(1 if failures else 0)
