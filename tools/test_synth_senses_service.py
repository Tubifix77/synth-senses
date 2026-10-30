#!/usr/bin/env python3
"""The program that keeps the phone's PC side running: never two copies, its
programs die with it, a stopped program is started again but not in a loop,
and commands still reach the receiver."""
import io
import json
import os
import subprocess
import sys
import tempfile
import textwrap
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth_senses_service as svc  # noqa: E402

failures = []


def check(label, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{'  ' + detail if detail else ''}")
    if not ok:
        failures.append(label)


print("\nthe three programs, and only one bridge")
progs = svc.programs("https://hub.example:8443", python="py")
names = [n for n, _ in progs]
check("receiver, bridge and port", names == ["receiver", "bridge", "port"], str(names))
bridge = dict(progs)["bridge"]
check("the bridge follows the receiver's percept log",
      bridge[-2:] == ["--follow", "percepts.jsonl"] and "potluck_bridge.py" in bridge[2], str(bridge))
check("bridge and port are both pointed at the hub given",
      all("https://hub.example:8443" in cmd for n, cmd in progs if n != "receiver"))

print("\nnever two copies")
probe = svc.take_lock(0)                     # any free port, to borrow its number
port = probe.getsockname()[1]
probe.close()
first = svc.take_lock(port)
check("the first copy takes the lock", first is not None)
check("a second copy does not", svc.take_lock(port) is None)
first.close()
again = svc.take_lock(port)
check("and the lock is free again once the first has gone", again is not None)
again.close()

held = svc.take_lock()
if held is not None:
    with tempfile.TemporaryDirectory() as d:
        code = svc.main(["--hub", "https://hub.example:8443", "--logs", d])
    check("main() exits 3 when another copy holds the lock", code == 3, str(code))
    held.close()
else:
    check("main() exits 3 when another copy holds the lock", True, "skipped: the lock port is in use here")

print("\na stopped program is started again, but not in a loop")


class Clock:
    t = 100.0

    def __call__(self):
        return self.t


class FakeProc:
    def __init__(self):
        self.returncode = None

    def poll(self):
        return self.returncode


clk = Clock()
spawned = []


def fake_spawn(child):
    p = FakeProc()
    spawned.append(p)
    return p


c = svc.Child("bridge", ["x"], None, fake_spawn, clock=clk, say=lambda m: None)
check("started on the first look", c.tend() and len(spawned) == 1)
check("left alone while it runs", not c.tend() and len(spawned) == 1)
spawned[-1].returncode = 1
clk.t += 1
check("not restarted within the backoff", not c.tend() and len(spawned) == 1)
clk.t += svc.BACKOFF_S
check("restarted once the backoff has passed", c.tend() and len(spawned) == 2, str(len(spawned)))
check("and counted", c.starts == 2)

print("\ncommands reach the receiver")


class Pipe(io.BytesIO):
    def flush(self):
        pass


class Receiver:
    def __init__(self):
        self.stdin = Pipe()

    def poll(self):
        return None


with tempfile.TemporaryDirectory() as d:
    path = os.path.join(d, "commands.jsonl")
    with open(path, "w", encoding="utf-8") as f:
        f.write('{"cmd": "old"}\n')
    rc = svc.Child("receiver", [], None, None)
    rc.proc = Receiver()
    stop = threading.Event()
    t = threading.Thread(target=svc.forward_commands, args=(path, rc, stop), daemon=True)
    t.start()
    time.sleep(0.3)
    with open(path, "a", encoding="utf-8") as f:
        f.write('{"cmd": "status"}\n')
    deadline = time.time() + 5
    while time.time() < deadline and b"status" not in rc.proc.stdin.getvalue():
        time.sleep(0.1)
    stop.set()
    t.join(timeout=5)                         # it holds the file open; Windows will not delete it
    got = rc.proc.stdin.getvalue()
    check("a line appended to the commands file is passed on", b'{"cmd": "status"}\n' in got, repr(got))
    check("one already there is not resent", b"old" not in got, repr(got))

print("\nPotluck is told which phone is sending, and nothing more")
p20 = {"model": "ANE-LX1", "manufacturer": "HUAWEI", "android": "9", "sdk_int": 28}
check("maker, model and Android version", svc.device_label(p20) == "HUAWEI ANE-LX1 · Android 9",
      repr(svc.device_label(p20)))
check("a maker the model already names is not said twice",
      svc.device_label({"manufacturer": "Google", "model": "Google Pixel 8", "android": "15"})
      == "Google Pixel 8 · Android 15")
check("no model, no label", svc.device_label({"manufacturer": "HUAWEI"}) is None
      and svc.device_label(None) is None)
check("padding and control characters do not reach the hub",
      svc.device_label({"manufacturer": "X\u0000", "model": " Y\n"}) == "X Y")
check("held to the hub's cap", len(svc.device_label({"model": "m" * 500})) == svc.DETAIL_MAX)
check("whatever else the reply carries stays out of the label",
      svc.device_label(dict(p20, serial="ABC123", id="7bcba62e8d58")) == "HUAWEI ANE-LX1 · Android 9")

asked = []
pres = svc.Presence(ask=lambda: asked.append(1))
up = [svc.Child(n, [], None, None) for n in ("receiver", "bridge", "port")]
for ch in up:
    ch.proc = Receiver()
check("idle before any phone", svc.report(pres, up) == ("idle", "no phone connected"))
pres.see("*** node connected from 192.168.0.40:51234 — commands are live ***")
check("a connect asks the phone what it is", asked == [1])
check("connected before it has answered, and named only as the desk would",
      svc.report(pres, up) == ("connected", "phone"), str(svc.report(pres, up)))
pres.see(json.dumps({"type": "status", "device_id": "7bcba62e8d58", "device": p20}))
check("connected, as the phone it is", svc.report(pres, up) == ("connected", "HUAWEI ANE-LX1 · Android 9"),
      str(svc.report(pres, up)))
pres.see(json.dumps({"seq": 1, "narration": 'it said "type": "status" aloud'}))
check("a percept is never read as a reply", svc.report(pres, up)[1] == "HUAWEI ANE-LX1 · Android 9")
pres.see("*** node 192.168.0.40:51234 disconnected (closed) ***")
check("a phone that leaves takes its label with it", svc.report(pres, up) == ("idle", "no phone connected"))
pres.see("*** node connected from 192.168.0.40:51300 — commands are live ***")
pres.see(json.dumps({"type": "status", "device": p20}))
pres.reset()
check("a receiver restart forgets its phones", svc.report(pres, up)[0] == "idle")
up[1].proc.poll = lambda: 1
check("a program down is an error, by name", svc.report(pres, up) == ("error", "bridge not running, restarting"))

receiver_src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "receiver.py"),
                    encoding="utf-8").read()
check("the receiver still says the lines this reads",
      'say(f"\\n' + svc.NODE_CONNECTED + "{node}" in receiver_src
      and "{node}" + svc.NODE_LEFT in receiver_src)

posts = []
halt = threading.Event()


def fake_post(path, body):
    posts.append((path, body))
    if len(posts) >= 3:
        halt.set()
    return 200, {"ok": True}, {}


said = []
svc.keep_reporting(fake_post, "Synth Senses", svc.Presence(ask=lambda: None), up[:1], halt,
                   said.append, period=0.01)
check("reports under the pairing name, to the integration list",
      all(p == "/api/integrations" and b["name"] == "Synth Senses" for p, b in posts), str(posts[:1]))
check("and its last report says it stopped", posts[-1][1]["state"] == "disconnected", str(posts[-1]))
check("an unchanged state is logged once", len(said) == 1, str(said))

print("\nits programs die with it, however it ends")
# A parent that spawns a child the way the service does, then is killed hard --
# TerminateProcess on Windows, SIGKILL here -- with no chance to clean up. The
# child ticks a file; if it outlived its parent, the file keeps growing. The
# control is the same parent without the guard, whose child must survive, or
# this test could not tell the guard working from a child that never ran.
HERE_DIR = os.path.dirname(os.path.abspath(__file__))


def orphan_survives(guarded):
    """-> (the child was ticking, it kept ticking after its parent was killed)"""
    with tempfile.TemporaryDirectory() as d:
        tick = os.path.join(d, "tick")
        child_code = ("import time\nwhile True:\n"
                      "    f = open(%r, 'a'); f.write('.'); f.close()\n    time.sleep(0.1)\n" % tick)
        parent_code = textwrap.dedent("""
            import subprocess, sys, time
            sys.path.insert(0, %r)
            import synth_senses_service as svc
            g = svc.KillWithParent()
            kw = g.popen_kwargs() if %r else {}
            p = subprocess.Popen([sys.executable, "-c", %r], **kw)
            if %r:
                g.adopt(p)
            print(p.pid, flush=True)
            time.sleep(60)
        """) % (HERE_DIR, guarded, child_code, guarded)
        parent = subprocess.Popen([sys.executable, "-c", parent_code], stdout=subprocess.PIPE)
        pid = int(parent.stdout.readline())

        def size():
            return os.path.getsize(tick) if os.path.exists(tick) else 0

        deadline = time.time() + 15
        while time.time() < deadline and size() < 5:
            time.sleep(0.1)
        ticking = size() >= 5
        parent.kill()
        parent.wait()
        parent.stdout.close()
        time.sleep(1.5)
        before = size()
        time.sleep(1.5)
        alive = size() > before
        if alive:  # the control's orphan, which nothing else will stop
            if sys.platform == "win32":
                subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True)
            else:
                try:
                    os.kill(pid, 9)
                except OSError:
                    pass
            time.sleep(0.5)
        return ticking, alive


ticking, alive = orphan_survives(guarded=False)
check("control: without the guard the child runs", ticking)
check("control: and outlives its killed parent", alive)
ticking, alive = orphan_survives(guarded=True)
check("with the guard the child runs", ticking)
check("and stops when its parent is killed", not alive)

print("\n%d failure(s)" % len(failures))
for f in failures:
    print("  - " + f)
sys.exit(1 if failures else 0)
