#!/usr/bin/env python3
"""The cell steering the phone: only a change is a command, nothing replays on
a restart, the switches end up showing the phone's own state, and nothing
outside the scope Tue chose can be asked for."""
import os
import sys
import threading

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import potluck_steer as ps  # noqa: E402

failures = []


def check(label, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{'  ' + detail if detail else ''}")
    if not ok:
        failures.append(label)


def model(**values):
    return {"resources": [{"name": "act/%s/%s" % (ps.INBOX, k), "value": v, "quality": "GOOD"}
                          for k, v in values.items()]
            + [{"name": "act/phone/battery", "value": 0.8}]}


print("\nreading the inbox")
check("its values and no other inbox's",
      ps.steer_values(model(sample=3, vision=True)) == {"sample": 3, "vision": True})
check("a value not yet written is not there",
      ps.steer_values({"resources": [{"name": "act/phone_steer/lens", "value": None}]}) == {})

print("\nonly a change is a command")
s = ps.Steer()
check("the first look is a baseline, so a restart replays nothing",
      s.commands(ps.steer_values(model(sample=7, vision=True, interval_s=5))) == [])
check("unchanged is silent", s.commands(ps.steer_values(model(sample=7, vision=True, interval_s=5))) == [])
check("a new sample number takes a sample",
      s.commands(ps.steer_values(model(sample=8, vision=True, interval_s=5))) == [{"cmd": "sample"}])
got = s.commands(ps.steer_values(model(sample=9, vision=False, interval_s=30)))
check("settings go as one set, before the sample",
      got == [{"cmd": "set", "interval_ms": 30000, "vision": False}, {"cmd": "sample"}], str(got))

print("\nout of range is clamped to what the phone accepts")
s = ps.Steer()
s.commands({})
check("interval below 1 s", s.commands({"interval_s": 0}) == [{"cmd": "set", "interval_ms": 1000}])
check("interval above 600 s", s.commands({"interval_s": 9999}) == [{"cmd": "set", "interval_ms": 600000}])
check("threshold above 1", s.commands({"threshold": 4.0}) == [{"cmd": "set", "threshold": 1.0}])
check("lens by ordinal", s.commands({"lens": 1}) == [{"cmd": "set", "lens": "front"}])
check("a lens that names nothing is ignored", s.commands({"lens": 7}) == [])
check("a switch written as a number", s.commands({"hearing": 1}) == [{"cmd": "set", "hearing": True}])

print("\nonly what Tue chose can be asked for")
asked = set(ps.SWITCHES) | {"interval_ms", "threshold", "lens"}
check("the switches are vision, hearing, speech and location",
      set(ps.SWITCHES) == {"vision", "hearing", "speech", "location"})
s = ps.Steer()
s.commands({})
everything = s.commands({n: 1 for n, _k, _u in ps.VALUES})
cmds = {c["cmd"] for c in everything}
keys = {k for c in everything for k in c if k != "cmd"}
check("nothing but set and sample reaches the phone", cmds == {"set", "sample"}, str(cmds))
check("and set only touches the chosen settings", keys <= asked, str(keys - asked))
check("no speak, forget or place naming in the inbox",
      not {"speak", "forget", "name_place", "voice"} & {n for n, _k, _u in ps.VALUES})

print("\nthe switches show what the phone says")
s = ps.Steer()
s.commands({"vision": True, "interval_s": 5})
s.phone_said({"type": "status", "interval_ms": 5000, "threshold": 0.35,
              "senses": {"vision": False, "lens": "back", "hearing": True, "speech": False,
                         "location": True}})
back = dict(s.write_backs())
check("what the hub holds wrong, or not at all, is queued, the counter included",
      back == {"vision": False, "threshold": 0.35, "lens": 0, "hearing": True, "speech": False,
               "location": True, "sample": 0}, str(back))
check("what it already holds is not", "interval_s" not in back)
s.wrote("vision", False)
check("a write the hub took is done", "vision" not in dict(s.write_backs()))
check("and is not then read back as a command", s.commands({"vision": False, "interval_s": 5}) == [])

s = ps.Steer()
s.commands({"sample": 41})
s.phone_said({"senses": {}})
check("a counter someone has written is never written over", "sample" not in dict(s.write_backs()))

s = ps.Steer()
s.commands({"vision": True, "sample": 1})
s.phone_said({"senses": {"vision": False}})
check("a write that failed is not marked as held, so it is not a command either",
      s.commands({"vision": True}) == [] and dict(s.write_backs()) == {"vision": False})

print("\nthe loop")
stop = threading.Event()
looks = [model(sample=1, vision=True), model(sample=2, vision=False)]
sent, writes, said = [], [], []


def get_model():
    if not looks:
        stop.set()
        return model(sample=2, vision=False)
    return looks.pop(0)


def write(name, value):
    writes.append((name, value))
    return True, "applied"


ps.steer_forever(get_model, write, sent.append, ps.Steer(), stop, said.append, every_s=0)
check("a set is followed by a status, so the switches get the truth",
      sent == [{"cmd": "set", "vision": False}, {"cmd": "status"}, {"cmd": "sample"}], str(sent))
check("and what was asked is in the service log", any("the cell asks" in m for m in said), str(said))

print("\nthe manifest")
check("an inbox, its values typed", ps.manifest_outputs()
      == "sample:u32 lens:u32 interval_s:u32 threshold:f32 vision:bool hearing:bool speech:bool location:bool")
check("within an inbox's twelve", len(ps.VALUES) <= 12)
import io, contextlib, json  # noqa: E401,E402
buf = io.StringIO()
with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(io.StringIO()):
    sys.argv = ["potluck_steer.py", "--manifest"]
    ps.main()
check("never stale: settings, not readings", json.loads(buf.getvalue())["config"]["stale_ms"] == 0)

print("\n%d failure(s)" % len(failures))
for f in failures:
    print("  - " + f)
sys.exit(1 if failures else 0)
