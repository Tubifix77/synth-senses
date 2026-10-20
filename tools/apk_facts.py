#!/usr/bin/env python3
"""Read the facts out of a built APK, so claims about it can be re-checked.

This project has a rule: resolve facts, do not recall them. The APK had been
opened once by hand and the findings written into docs/VALIDATION.md, which
is better than assuming — but a one-off check decays the moment the build
changes, and lowering `minSdk` to 28 made that table stale the same evening.

So this makes it repeatable, in pure stdlib, with no Android SDK and no
`aapt`:

    python3 tools/apk_facts.py app-debug.apk
    python3 tools/apk_facts.py app-debug.apk --expect-min-sdk 28

The interesting part is the manifest. An APK's AndroidManifest.xml is binary
AXML, not text, so there is a small reader below for it. It understands only
what is needed here -- the string pool and element attributes -- which is a
fraction of the format and all that a fact table needs.

Exit code 1 if an --expect check fails, so CI can use it as a gate.
"""
import argparse
import collections
import struct
import sys
import zipfile


def utf8_streams():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):
            pass


# ------------------------------------------------------------- binary AXML
#
# Chunk types from AOSP's ResourceTypes.h. Only the handful used here.
RES_STRING_POOL = 0x0001
RES_XML_START_ELEMENT = 0x0102
UTF8_FLAG = 1 << 8

# TypedValue data types, again only the ones a manifest actually uses.
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12


def _u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def _u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def read_string_pool(data, start):
    """The pool every name and value in the file is an index into."""
    count = _u32(data, start + 8)
    flags = _u32(data, start + 16)
    strings_start = _u32(data, start + 20)
    utf8 = bool(flags & UTF8_FLAG)
    offsets = [_u32(data, start + 28 + 4 * i) for i in range(count)]
    base = start + strings_start
    out = []
    for off in offsets:
        p = base + off
        if utf8:
            # two varint-ish lengths: characters, then bytes
            n = data[p]
            p += 2 if n & 0x80 else 1
            n = data[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p + 1]
                p += 2
            else:
                p += 1
            out.append(data[p:p + n].decode("utf-8", "replace"))
        else:
            n = _u16(data, p)
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | _u16(data, p)
                p += 2
            out.append(data[p:p + n * 2].decode("utf-16-le", "replace"))
    return out


def parse_axml(data):
    """-> [(element_name, {attr: value}), ...] in document order."""
    pool = []
    pos = 8                                   # past the file header
    elements = []
    while pos + 8 <= len(data):
        ctype = _u16(data, pos)
        size = _u32(data, pos + 4)
        if size <= 0 or pos + size > len(data):
            break
        if ctype == RES_STRING_POOL:
            pool = read_string_pool(data, pos)
        elif ctype == RES_XML_START_ELEMENT:
            name = pool[_u32(data, pos + 20)] if pool else ""
            attr_start = _u16(data, pos + 24)
            attr_size = _u16(data, pos + 26)
            attr_count = _u16(data, pos + 28)
            attrs = {}
            for i in range(attr_count):
                # attributeStart counts from the start of the attribute
                # extension struct, which begins 16 bytes into the node --
                # past the chunk header, lineNumber and comment -- not from
                # the chunk itself. Off by those 16 and every attribute name
                # is a garbage pool index, which reads as "no attributes"
                # rather than as an error.
                a = pos + 16 + attr_start + i * attr_size
                a_name = _u32(data, a + 4)
                raw = _u32(data, a + 8)
                dtype = data[a + 15]
                value = _u32(data, a + 16)
                key = pool[a_name] if a_name < len(pool) else str(a_name)
                if dtype == TYPE_STRING:
                    attrs[key] = pool[value] if value < len(pool) else ""
                elif dtype in (TYPE_INT_DEC, TYPE_INT_HEX):
                    attrs[key] = value
                elif dtype == TYPE_INT_BOOLEAN:
                    attrs[key] = bool(value)
                elif raw != 0xFFFFFFFF and raw < len(pool):
                    attrs[key] = pool[raw]
                else:
                    attrs[key] = value
                # an unnamed attribute is one whose name lives only in the
                # resource map; the manifest facts we want all have names
            elements.append((name, attrs))
        pos += size
    return elements


# Android's ServiceInfo.FOREGROUND_SERVICE_TYPE_* flags. The compiled
# manifest holds the OR of them as an int, so 200 is camera|microphone|
# location rather than anything to look up.
FGS_TYPES = [
    (1, "dataSync"), (2, "mediaPlayback"), (4, "phoneCall"), (8, "location"),
    (16, "connectedDevice"), (32, "mediaProjection"), (64, "camera"),
    (128, "microphone"), (256, "health"), (512, "remoteMessaging"),
    (1024, "systemExempted"), (2048, "shortService"),
    (4096, "fileManagement"), (8192, "mediaProcessing"),
    (16384, "specialUse"),
]


def fgs_names(value):
    if value is None:
        return "none declared"
    if isinstance(value, str):
        return value
    named = [name for bit, name in FGS_TYPES if value & bit]
    left = value - sum(bit for bit, _ in FGS_TYPES if value & bit)
    if left:
        named.append(f"+{left}")
    return f"{value} = {'|'.join(named) or 'none'}"


# ----------------------------------------------------------------- the facts

def facts(path):
    z = zipfile.ZipFile(path)
    names = z.namelist()
    elements = parse_axml(z.read("AndroidManifest.xml"))

    out = {"entries": len(names)}
    for name, attrs in elements:
        if name == "uses-sdk":
            out["minSdkVersion"] = attrs.get("minSdkVersion")
            out["targetSdkVersion"] = attrs.get("targetSdkVersion")
        elif name == "manifest":
            out["compileSdkVersion"] = attrs.get("compileSdkVersion")
            out["package"] = attrs.get("package")
            out["versionName"] = attrs.get("versionName")

    out["permissions"] = sorted(
        a.get("name") for n, a in elements
        if n == "uses-permission" and isinstance(a.get("name"), str))
    out["features"] = sorted(
        a.get("name") for n, a in elements
        if n == "uses-feature" and isinstance(a.get("name"), str))
    out["services"] = sorted(
        a.get("name") for n, a in elements
        if n == "service" and isinstance(a.get("name"), str))
    # foregroundServiceType is a flags int in the compiled manifest, not the
    # "camera|microphone|location" string the source spells it with, so it is
    # reported as-is rather than filtered to str and silently lost.
    for n, a in elements:
        if n == "service" and a.get("foregroundServiceType") is not None:
            out["foregroundServiceType"] = a["foregroundServiceType"]

    out["dex"] = sorted(n for n in names if n.endswith(".dex"))
    out["assets"] = sorted(n for n in names if n.startswith("assets/"))
    out["abis"] = sorted({n.split("/")[1] for n in names
                          if n.startswith("lib/") and n.count("/") >= 2})
    out["size_mib"] = round(
        sum(z.getinfo(n).compress_size for n in names) / 1048576, 1)

    # the app's own classes, which is the check that the build really packaged
    # what the source says rather than an empty shell
    own = collections.Counter()
    for dex in out["dex"]:
        blob = z.read(dex)
        for marker in (b"Lnet/synthsenses/", ):
            own[dex] = blob.count(marker)
    out["own_class_refs"] = dict(own)
    return out


def main():
    utf8_streams()
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("apk")
    ap.add_argument("--expect-min-sdk", type=int)
    ap.add_argument("--expect-target-sdk", type=int)
    ap.add_argument("--expect-compile-sdk", type=int)
    ap.add_argument("--forbid", action="append", default=[],
                    help="a packaged path that must NOT be present, e.g. "
                         "assets/README.md (repeatable)")
    args = ap.parse_args()

    f = facts(args.apk)
    print(f"package        {f.get('package')}  v{f.get('versionName')}")
    print(f"compileSdk     {f.get('compileSdkVersion')}")
    print(f"minSdk         {f.get('minSdkVersion')}")
    print(f"targetSdk      {f.get('targetSdkVersion')}")
    print(f"size           {f['size_mib']} MiB compressed, {f['entries']} entries")
    print(f"abis           {', '.join(f['abis']) or 'none'}")
    print(f"dex            {len(f['dex'])} files")
    print(f"own classes    {sum(f['own_class_refs'].values())} references to "
          f"Lnet/synthsenses/ across them")
    print(f"permissions    {len(f['permissions'])}")
    for p in f["permissions"]:
        print(f"               {p}")
    print(f"fgs type       {fgs_names(f.get('foregroundServiceType'))}")
    print(f"assets         {len(f['assets'])} files")

    failures = []

    def expect(label, got, want):
        if want is None:
            return
        ok = got == want
        print(f"  {'PASS' if ok else 'FAIL'}  {label}: {got} (expected {want})")
        if not ok:
            failures.append(label)

    if args.expect_min_sdk or args.expect_target_sdk or args.expect_compile_sdk \
            or args.forbid:
        print("\nchecks")
    expect("minSdk", f.get("minSdkVersion"), args.expect_min_sdk)
    expect("targetSdk", f.get("targetSdkVersion"), args.expect_target_sdk)
    expect("compileSdk", f.get("compileSdkVersion"), args.expect_compile_sdk)
    for forbidden in args.forbid:
        present = forbidden in zipfile.ZipFile(args.apk).namelist()
        print(f"  {'FAIL' if present else 'PASS'}  not shipped: {forbidden}")
        if present:
            failures.append(forbidden)

    if failures:
        print(f"\n{len(failures)} failure(s)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
