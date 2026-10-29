#!/usr/bin/env python3
"""The phone's whole PC side, as one program.

    python3 tools/synth_senses_service.py --hub https://192.168.0.7:8443

Three programs carry the phone's senses on this PC: the receiver the phone
connects to, the bridge that writes what it hears into Potluck, and the port
that serves it from Potluck to a companion. Each was started by hand, in a
terminal or in a Claude session, and each stopped when that did -- three times
in one week Potluck showed the phone as STALE because a session had ended. Tue
put the fix plainly: Potluck is meant to be one thing, and these should be
running when its icon is.

So this runs all three, starts any that stops again (no more often than once
in BACKOFF_S, so a program that cannot start is not restarted in a loop), and
stops them all when it stops. It is the one command Potluck's tray keeps
running beside the hub, the way it keeps the hub and the PC's host node.

Things it guarantees, because each one has gone wrong without it:

- **Never two bridges.** Two copies writing the same values halve each
  other's rate budget, and the first real 429 was exactly that. A second copy
  of this program finds the lock taken and exits with code 3 at once.
- **Its programs die with it.** On Windows, ending a process leaves its
  children running, so a tray that stopped this would have left an orphaned
  bridge writing beside the next one. The children are put in a job object
  that the system closes, killing them, when this process ends by any means.
  On Linux they get a parent-death signal instead.
- **Logs that end.** The receiver prints every percept, so each program's
  output goes to a log that rolls over at LOG_BYTES, keeping one older file.
- **Commands still reach the phone.** The receiver takes commands as JSON
  lines on its stdin. Lines appended to the commands file (--commands) are
  passed to it, and its replies are in receiver.log.

Exit codes: 0 stopped when asked, 2 no pairing (pair once with
potluck_bridge.py --pair), 3 already running.
"""
import argparse
import json
import os
import signal
import socket
import subprocess
import sys
import threading
import time
from logging.handlers import RotatingFileHandler

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

#: Held for as long as this runs: the single-instance lock. Beside the
#: receiver's 8077, the beacon's 8078 and the port's 8079.
LOCK_PORT = 8076

#: The least time between two starts of the same program.
BACKOFF_S = 30.0

#: Each log rolls over at this size, keeping one older file.
LOG_BYTES = 5 * 1024 * 1024


def programs(hub, python=sys.executable):
    """-> [(name, argv)], all run from the repo root, where the pairing and the
    percept log live."""
    tool = lambda name: os.path.join(HERE, name)  # noqa: E731
    return [
        ("receiver", [python, "-u", tool("receiver.py")]),
        ("bridge", [python, "-u", tool("potluck_bridge.py"), "--hub", hub, "--follow", "percepts.jsonl"]),
        ("port", [python, "-u", tool("potluck_port.py"), "--hub", hub]),
    ]


def take_lock(port=LOCK_PORT):
    """-> a bound socket while no other copy runs, else None. The system frees
    it however this process ends, so a crash never leaves a stale lock."""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    if sys.platform == "win32":
        # Windows lets a second bind succeed unless the port is held exclusively.
        s.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    try:
        s.bind(("127.0.0.1", port))
        s.listen(1)
    except OSError:
        s.close()
        return None
    return s


class KillWithParent:
    """Children that end when this process ends, however it ends."""

    def __init__(self):
        self.job = None
        if sys.platform == "win32":
            self.job = self._windows_job()

    @staticmethod
    def _windows_job():
        import ctypes
        from ctypes import wintypes

        class BASIC(ctypes.Structure):
            _fields_ = [("PerProcessUserTimeLimit", ctypes.c_int64), ("PerJobUserTimeLimit", ctypes.c_int64),
                        ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                        ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                        ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD),
                        ("SchedulingClass", wintypes.DWORD)]

        class IO(ctypes.Structure):
            _fields_ = [(n, ctypes.c_uint64) for n in ("ReadOperationCount", "WriteOperationCount",
                                                       "OtherOperationCount", "ReadTransferCount",
                                                       "WriteTransferCount", "OtherTransferCount")]

        class EXTENDED(ctypes.Structure):
            _fields_ = [("BasicLimitInformation", BASIC), ("IoInfo", IO),
                        ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                        ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]

        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.CreateJobObjectW.restype = wintypes.HANDLE
        k32.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        k32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        job = k32.CreateJobObjectW(None, None)
        if not job:
            return None
        info = EXTENDED()
        info.BasicLimitInformation.LimitFlags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not k32.SetInformationJobObject(job, 9, ctypes.byref(info), ctypes.sizeof(info)):  # Extended info
            return None
        return (k32, job)

    def popen_kwargs(self):
        if sys.platform == "win32":
            return {"creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0)}
        return {"preexec_fn": _die_with_parent}

    def adopt(self, proc):
        if self.job is not None:
            k32, job = self.job
            k32.AssignProcessToJobObject(job, int(proc._handle))


def _die_with_parent():
    try:
        import ctypes
        ctypes.CDLL("libc.so.6", use_errno=True).prctl(1, signal.SIGTERM)  # PR_SET_PDEATHSIG
    except (OSError, AttributeError):
        pass


def make_log(folder, name):
    os.makedirs(folder, exist_ok=True)
    return RotatingFileHandler(os.path.join(folder, name + ".log"), maxBytes=LOG_BYTES,
                               backupCount=1, encoding="utf-8", delay=True)


class Child:
    """One program, kept running."""

    def __init__(self, name, argv, log, spawn, clock=time.monotonic, say=print):
        self.name, self.argv, self.log = name, argv, log
        self.spawn, self.clock, self.say = spawn, clock, say
        self.proc = None
        self.last_start = None
        self.starts = 0

    def running(self):
        return self.proc is not None and self.proc.poll() is None

    def tend(self):
        """One look; True when it (re)started the program."""
        if self.running():
            return False
        if self.proc is not None:
            self.say("%s stopped (exit %s)" % (self.name, self.proc.returncode))
            self.proc = None
        if self.last_start is not None and self.clock() - self.last_start < BACKOFF_S:
            return False
        self.proc = self.spawn(self)
        self.last_start = self.clock()
        self.starts += 1
        self.say("%s started%s" % (self.name, "" if self.starts == 1 else " again (%d)" % self.starts))
        return True

    def stop(self):
        if self.running():
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()


def pump(stream, log):
    """Copy a program's output into its log, a line at a time."""
    import logging
    for raw in iter(stream.readline, b""):
        line = raw.decode("utf-8", "replace").rstrip("\r\n")
        log.emit(logging.makeLogRecord({"msg": line, "levelno": 20, "levelname": "INFO"}))
    stream.close()


def forward_commands(path, child, stop):
    """Pass lines appended to `path` to the receiver's stdin, from the end of
    the file as it is now: a command left there from before is not resent."""
    open(path, "a", encoding="utf-8").close()
    with open(path, encoding="utf-8") as f:
        f.seek(0, os.SEEK_END)
        while not stop.is_set():
            line = f.readline()
            if not line:
                time.sleep(0.5)
                continue
            proc = child.proc
            if proc is None or proc.poll() is not None or proc.stdin is None:
                continue
            try:
                proc.stdin.write(line.encode("utf-8"))
                proc.stdin.flush()
            except OSError:
                pass


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--hub", required=True, help="https://<host>:8443 of the Potluck hub")
    ap.add_argument("--logs", default=os.path.join(ROOT, "logs"), help="where each program's log goes")
    ap.add_argument("--commands", default=None,
                    help="a file whose appended JSON lines are sent to the phone (default: <logs>/commands.jsonl)")
    args = ap.parse_args(argv)

    os.makedirs(args.logs, exist_ok=True)
    own = make_log(args.logs, "service")
    stdout = sys.stdout

    def say(msg):
        line = "%s  %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
        import logging
        own.emit(logging.makeLogRecord({"msg": line, "levelno": 20, "levelname": "INFO"}))
        if stdout is not None:
            try:
                print(line, file=stdout, flush=True)
            except (OSError, ValueError):
                pass

    lock = take_lock()
    if lock is None:
        say("already running (127.0.0.1:%d is held), so not starting a second copy" % LOCK_PORT)
        return 3
    try:
        with open(os.path.join(ROOT, ".potluck-token.json"), encoding="utf-8") as f:
            if not json.load(f).get("token"):
                raise ValueError("no token")
    except (OSError, ValueError) as e:
        say("no pairing in .potluck-token.json (%s): pair once with potluck_bridge.py --pair" % e)
        return 2

    guard = KillWithParent()

    def spawn(child):
        p = subprocess.Popen(child.argv, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             stdin=subprocess.PIPE if child.name == "receiver" else subprocess.DEVNULL,
                             **guard.popen_kwargs())
        guard.adopt(p)
        threading.Thread(target=pump, args=(p.stdout, child.log), daemon=True).start()
        return p

    children = [Child(name, cmd, make_log(args.logs, name), spawn, say=say)
                for name, cmd in programs(args.hub)]
    stop = threading.Event()

    def ask_stop(*_):
        stop.set()

    for sig in ("SIGINT", "SIGTERM", "SIGBREAK"):
        if hasattr(signal, sig):
            signal.signal(getattr(signal, sig), ask_stop)

    receiver = children[0]
    threading.Thread(target=forward_commands,
                     args=(args.commands or os.path.join(args.logs, "commands.jsonl"), receiver, stop),
                     daemon=True).start()
    say("running the receiver, the bridge and the port against %s; logs in %s" % (args.hub, args.logs))
    try:
        while not stop.is_set():
            for c in children:
                try:
                    c.tend()
                except OSError as e:
                    say("%s did not start: %s" % (c.name, e))
                    c.last_start = c.clock()
            stop.wait(2.0)
    finally:
        for c in reversed(children):
            c.stop()
        say("stopped")
        lock.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
