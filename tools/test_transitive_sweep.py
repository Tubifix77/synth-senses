#!/usr/bin/env python3
"""Tests for the pure logic inside transitive_sweep.py. No network.

The tool is now what dependency decisions get made on, and it already earned
that by predicting the okhttp 5.5.0 rejection down to the artifact name before
CI confirmed it. But its version ordering is the subtle part — Gradle's rules
are not string order and not naive tuple order — and a regression there would
not crash, it would quietly give the wrong answer and get believed.

Run: python3 tools/test_transitive_sweep.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import transitive_sweep as ts  # noqa: E402

failures = []


def check(label, got, want):
    ok = got == want
    print(f"  {'PASS' if ok else 'FAIL'}  {label}" + ("" if ok else f"   got {got!r}, want {want!r}"))
    if not ok:
        failures.append(label)


print("version ordering (Gradle's rules, not string order)")
# the trap that makes naive string comparison wrong
check("1.10.0 beats 1.9.0", ts.vmax(["1.9.0", "1.10.0"]), "1.10.0")
check("9 beats 10 nowhere", ts.vmax(["1.10.0", "1.9.0"]), "1.10.0")
# the real case this tool was built for
check("lifecycle 2.11.0 is newest", ts.vmax(["2.10.0", "2.9.4", "2.11.0", "2.8.7"]), "2.11.0")
check("AGP 8.13.2 beats 8.9.0", ts.vmax(["8.9.0", "8.13.2"]), "8.13.2")
check("okhttp 5.5.0 beats 5.4.0", ts.vmax(["5.4.0", "5.5.0"]), "5.5.0")
check("AGP 9.4.1 beats 8.13.2", ts.vmax(["8.13.2", "9.4.1"]), "9.4.1")
# a longer numeric version wins over its prefix
check("1.0.0 beats 1.0", ts.vmax(["1.0", "1.0.0"]), "1.0.0")
# qualifiers sort below the release they qualify
check("a release beats its alpha", ts.vmax(["1.0-alpha", "1.0"]), "1.0")
check("rc beats alpha", ts.vmax(["1.0-alpha", "1.0-rc"]), "1.0-rc")
check("empty list is None", ts.vmax([]), None)
check("None entries are ignored", ts.vmax([None, "1.2.3"]), "1.2.3")

print("\nversion range handling")
check("a pinned range is just the version", ts.normalize_version("[2.6.1]"), ("2.6.1", False))
check("an open range takes the lower bound, flagged",
      ts.normalize_version("[18.0,19.0)"), ("18.0", True))
check("a plain version is untouched", ts.normalize_version("1.2.3"), ("1.2.3", False))
check("None stays None", ts.normalize_version(None), (None, False))

print("\nAGP version tuples, for ceiling comparison")
check("9.4.1", ts.agp_tuple("9.4.1"), (9, 4, 1))
check("8.1.1", ts.agp_tuple("8.1.1"), (8, 1, 1))
check("short versions pad", ts.agp_tuple("9.1"), (9, 1, 0))
check("missing means no requirement", ts.agp_tuple(None), (0, 0, 0))
check("9.1.0 is above 8.13.2", ts.agp_tuple("9.1.0") > ts.agp_tuple("8.13.2"), True)
check("8.13.2 is above 8.9.1", ts.agp_tuple("8.13.2") > ts.agp_tuple("8.9.1"), True)

print("\nreading the ceiling out of this repo's own build files")
root = os.path.dirname(HERE)
detected = ts.detect_ceiling(
    os.path.join(root, "app", "build.gradle.kts"),
    os.path.join(root, "build.gradle.kts"),
)
# If these fail, either the build moved or the tool stopped being able to read
# it -- and a tool that silently checks against the wrong ceiling is worse than
# no tool, which is why this is asserted rather than assumed.
check("compileSdk", detected["sdk"], 37)
check("minSdk", detected["minsdk"], 29)
check("AGP", detected["agp"], "9.4.1")

print("\nparsing the dependency list")
deps, platforms = ts.parse_build_file(os.path.join(root, "app", "build.gradle.kts"), "debug")
names = {f"{d.g}:{d.a}" for d in deps}
check("the Compose BOM is seen as a platform", len(platforms), 1)
check("and it is the BOM", f"{platforms[0].g}:{platforms[0].a}",
      "androidx.compose:compose-bom")
for coord in ("androidx.core:core-ktx", "com.squareup.okhttp3:okhttp",
              "com.google.mediapipe:tasks-audio", "androidx.camera:camera-core"):
    check(f"sees {coord}", coord in names, True)
check("debugImplementation is included for the debug variant",
      "androidx.compose.ui:ui-tooling" in names, True)
check("test-only dependencies are not in the app graph",
      any(n.startswith("junit:") or n.startswith("org.json:") for n in names), False)

print("\ncommented-out dependencies must not count")
import tempfile  # noqa: E402
with tempfile.TemporaryDirectory() as d:
    p = os.path.join(d, "build.gradle.kts")
    with open(p, "w", encoding="utf-8") as f:
        f.write('dependencies {\n'
                '    implementation("real:one:1.0")\n'
                '    // implementation("ghost:two:2.0")\n'
                '    implementation("real:three:3.0") // trailing note\n'
                '}\n')
    deps2, _ = ts.parse_build_file(p, "debug")
    got = sorted(f"{x.g}:{x.a}:{x.version}" for x in deps2)
    check("only the live ones", got, ["real:one:1.0", "real:three:3.0"])

print(f"\n{len(failures)} failure(s)")
for f in failures:
    print("  -", f)
sys.exit(1 if failures else 0)
