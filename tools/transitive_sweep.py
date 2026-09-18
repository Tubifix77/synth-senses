#!/usr/bin/env python3
"""transitive_sweep.py -- check EVERY AAR in the app's resolved runtime graph, not
just the direct dependencies.

Why: AGP's :app:checkDebugAarMetadata rejects any AAR in the *resolved* graph whose
META-INF/com/android/build/gradle/aar-metadata.properties demands a newer
compileSdk or AGP than the project has. compat_sweep.py only checked direct
dependencies, which is exactly how
androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0 got through: it is
pulled in by activity-compose and Compose UI at 2.9.x/2.8.x, then aligned upward
to 2.11.0 by the sibling constraints that every lifecycle 2.11.0 artifact carries.

This script emulates Gradle's resolution closely enough to catch that before a
CI cycle is spent on it:
  * reads Gradle Module Metadata (.module) when the POM advertises it, POMs
    otherwise (parents, properties, dependencyManagement, BOM imports)
  * selects the Android runtime variant of each module and follows `available-at`
    redirects (KMP umbrella module -> -android artifact)
  * applies BOM platforms and dependency constraints, highest-version-wins,
    iterated to a fixed point
  * then reads each AAR's aar-metadata.properties and manifest minSdk with HTTP
    range requests, so a 30 MB AAR costs a few KB

Pure stdlib. Everything fetched is cached in .mvncache/ next to this script.

usage:
  python transitive_sweep.py [--build app/build.gradle.kts] [--sdk 36] [--agp 8.13.2]
                             [--minsdk 29] [--set group:artifact=version ...]
                             [--force group:artifact=version ...] [--all] [--quiet]

  --set    replace (or add) a direct dependency's version -- try a fix before editing
  --force  simulate resolutionStrategy.force / strictly for a module
  --all    list every resolved module, not only the AARs
Exit code 1 if anything in the graph would fail checkDebugAarMetadata.

Approximations, stated honestly: dependency `excludes` are ignored (over-approximates
the graph, so it can only produce extra rows, never miss one); a version range is
taken at its lower bound and flagged; variant matching is by usage/platform/build
type attributes rather than Gradle's full attribute algebra.
"""
import argparse
import functools
import hashlib
import io
import json
import os
import re
import sys
import threading
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

GOOGLE = "https://dl.google.com/dl/android/maven2/"
CENTRAL = "https://repo1.maven.org/maven2/"
CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".mvncache")
UA = {"User-Agent": "transitive-sweep/1.0 (stdlib urllib)"}
_lock = threading.Lock()
WARNINGS = []


def warn(msg):
    with _lock:
        if msg not in WARNINGS:
            WARNINGS.append(msg)


# ----------------------------------------------------------------- fetching
class NotFound(Exception):
    pass


def _cache_path(url):
    h = hashlib.sha1(url.encode()).hexdigest()[:16]
    tail = re.sub(r"[^A-Za-z0-9._-]", "_", url.rsplit("/", 1)[-1])[:80]
    return os.path.join(CACHE_DIR, f"{h}-{tail}")


def fetch(url, cache=True):
    p = _cache_path(url)
    if cache and os.path.exists(p):
        with open(p, "rb") as f:
            data = f.read()
        if data == b"__404__":
            raise NotFound(url)
        return data
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=90) as r:
            data = r.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            if cache:
                _store(p, b"__404__")
            raise NotFound(url)
        raise
    if cache:
        _store(p, data)
    return data


def _store(path, data):
    os.makedirs(CACHE_DIR, exist_ok=True)
    tmp = f"{path}.{threading.get_ident()}.tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def repos_for(group):
    google_first = ("androidx.", "com.android", "com.google.android", "com.google.mlkit",
                    "com.google.mediapipe", "com.google.firebase", "com.google.ar",
                    "com.google.testing.platform", "com.google.oboe")
    if group.startswith(google_first):
        return [GOOGLE, CENTRAL]
    return [CENTRAL, GOOGLE]


def base_url(repo, g, a, v):
    return f"{repo}{g.replace('.', '/')}/{a}/{v}/"


def fetch_first(g, a, v, filename):
    for repo in repos_for(g):
        try:
            return repo, fetch(base_url(repo, g, a, v) + filename)
        except NotFound:
            continue
    raise NotFound(f"{g}:{a}:{v} {filename}")


# ----------------------------------------------------------------- versions
SPECIAL = {"dev": -1, "rc": 1, "snapshot": 2, "final": 3, "ga": 4, "release": 5, "sp": 6}


def _vparts(v):
    return re.findall(r"\d+|[A-Za-z]+", v)


def vcmp(a, b):
    """Gradle's version ordering (Declaring Versions -> Version ordering)."""
    pa, pb = _vparts(a), _vparts(b)
    for i in range(max(len(pa), len(pb))):
        if i >= len(pa):
            return -1 if pb[i].isdigit() else 1
        if i >= len(pb):
            return 1 if pa[i].isdigit() else -1
        x, y = pa[i], pb[i]
        if x == y:
            continue
        if x.isdigit() and y.isdigit():
            return (int(x) > int(y)) - (int(x) < int(y))
        if x.isdigit():
            return 1
        if y.isdigit():
            return -1
        sx, sy = SPECIAL.get(x.lower(), 0), SPECIAL.get(y.lower(), 0)
        if sx != sy:
            return (sx > sy) - (sx < sy)
        return (x > y) - (x < y)
    return 0


vkey = functools.cmp_to_key(vcmp)


def vmax(versions):
    versions = [v for v in versions if v]
    return max(versions, key=vkey) if versions else None


def normalize_version(v):
    """'[2.6.1]' -> '2.6.1'; '[18.0,19.0)' -> '18.0' (flagged); plain stays plain."""
    if v is None:
        return None, False
    v = v.strip()
    m = re.fullmatch(r"[\[(]\s*([^,\])]*)\s*(?:,\s*([^\])]*))?\s*[\])]", v)
    if m:
        lo, hi = m.group(1).strip(), (m.group(2) or "").strip()
        if m.group(2) is None:
            return lo, False
        return (lo or hi or None), True
    return v, False


def agp_tuple(s):
    if not s:
        return (0, 0, 0)
    parts = [int(p) if p.isdigit() else 0 for p in re.split(r"[.-]", s)][:3]
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts)


# ----------------------------------------------------------------- model
class Dep:
    __slots__ = ("g", "a", "version", "strictly", "platform", "ranged")

    def __init__(self, g, a, version, strictly=None, platform=False, ranged=False):
        self.g, self.a, self.version = g, a, version
        self.strictly, self.platform, self.ranged = strictly, platform, ranged

    @property
    def key(self):
        return (self.g, self.a)

    def __repr__(self):
        return f"{self.g}:{self.a}:{self.version}"


class ModuleInfo:
    def __init__(self, g, a, v, repo):
        self.g, self.a, self.v, self.repo = g, a, v, repo
        self.deps = []
        self.constraints = []
        self.artifact_url = None      # the .aar / .jar this module contributes
        self.kind = None              # 'aar' | 'jar' | None
        self.redirect = None          # (g, a, v) for KMP umbrella modules
        self.source = None            # 'gmm' | 'pom'

    @property
    def key(self):
        return (self.g, self.a)

    def __repr__(self):
        return f"{self.g}:{self.a}:{self.v}"


_module_cache = {}


def load_module(g, a, v):
    ck = (g, a, v)
    with _lock:
        if ck in _module_cache:
            return _module_cache[ck]
    try:
        info = _load_module(g, a, v)
    except NotFound as e:
        warn(f"could not resolve {g}:{a}:{v} ({e})")
        info = None
    except Exception as e:  # keep going; report at the end
        warn(f"error loading {g}:{a}:{v}: {type(e).__name__}: {e}")
        info = None
    with _lock:
        _module_cache[ck] = info
    return info


def _load_module(g, a, v):
    repo, pom = fetch_first(g, a, v, f"{a}-{v}.pom")
    info = ModuleInfo(g, a, v, repo)
    if b"published-with-gradle-metadata" in pom:
        try:
            mod = json.loads(fetch(base_url(repo, g, a, v) + f"{a}-{v}.module").decode("utf-8"))
            _parse_gmm(mod, info)
            info.source = "gmm"
            return info
        except NotFound:
            pass
    _parse_pom(pom, info)
    info.source = "pom"
    return info


# ----------------------------------------------------------------- Gradle Module Metadata
def _pick_variant(variants):
    cands = []
    for var in variants:
        at = var.get("attributes", {}) or {}
        if at.get("org.gradle.usage") != "java-runtime":
            continue
        if at.get("org.gradle.category", "library") != "library":
            continue
        name = var.get("name", "").lower()
        if "sources" in name or "javadoc" in name:
            continue
        score = 0
        pt = at.get("org.jetbrains.kotlin.platform.type")
        if pt == "androidJvm":
            score += 100
        elif pt == "jvm":
            score += 50
        elif pt is None:
            score += 40
        else:
            score -= 100                       # native / js / wasm
        if at.get("com.android.build.api.attributes.BuildTypeAttr") == "release":
            score += 10
        if at.get("org.gradle.jvm.environment") == "android":
            score += 5
        cands.append((score, var))
    if not cands:
        return None
    cands.sort(key=lambda x: -x[0])
    return cands[0][1]


def _gmm_dep(d):
    ver = d.get("version") or {}
    raw = ver.get("strictly") or ver.get("requires") or ver.get("prefers")
    version, ranged = normalize_version(raw)
    attrs = d.get("attributes") or {}
    return Dep(d["group"], d["module"], version, strictly=ver.get("strictly"),
               platform=(attrs.get("org.gradle.category") == "platform"), ranged=ranged)


def _parse_gmm(mod, info):
    var = _pick_variant(mod.get("variants", []))
    if var is None:
        warn(f"{info}: no java-runtime library variant in .module; treating as leaf")
        return
    avail = var.get("available-at")
    if avail:
        tgt = (avail["group"], avail["module"], avail["version"])
        info.redirect = tgt
        info.deps = [Dep(*tgt)]
        # constraints declared on the umbrella still count
        info.constraints = [_gmm_dep(c) for c in var.get("dependencyConstraints", [])]
        return
    info.deps = [_gmm_dep(d) for d in var.get("dependencies", [])]
    info.constraints = [_gmm_dep(c) for c in var.get("dependencyConstraints", [])]
    for f in var.get("files", []):
        url = f.get("url", "")
        if url.endswith(".aar") or url.endswith(".jar"):
            info.artifact_url = base_url(info.repo, info.g, info.a, info.v) + url
            info.kind = "aar" if url.endswith(".aar") else "jar"
            break


# ----------------------------------------------------------------- POM
def _ns(root):
    return root.tag.split("}")[0] + "}" if root.tag.startswith("{") else ""


def _text(el, ns, name):
    x = el.find(ns + name)
    return x.text.strip() if x is not None and x.text and x.text.strip() else None


def _pom_chain(root):
    """[child, parent, grandparent, ...] as parsed roots."""
    chain = [root]
    cur = root
    for _ in range(10):
        ns = _ns(cur)
        par = cur.find(ns + "parent")
        if par is None:
            break
        pg, pa, pv = _text(par, ns, "groupId"), _text(par, ns, "artifactId"), _text(par, ns, "version")
        if not (pg and pa and pv):
            break
        try:
            _, pb = fetch_first(pg, pa, pv, f"{pa}-{pv}.pom")
        except NotFound:
            warn(f"parent POM {pg}:{pa}:{pv} not found")
            break
        cur = ET.fromstring(pb)
        chain.append(cur)
    return chain


def _interp(s, props):
    if s is None:
        return None
    for _ in range(8):
        keys = re.findall(r"\$\{([^}]+)\}", s)
        if not keys:
            break
        changed = False
        for k in keys:
            if k in props:
                s = s.replace("${" + k + "}", props[k])
                changed = True
        if not changed:
            break
    return s


_depmgmt_cache = {}


def pom_depmgmt(g, a, v):
    """dependencyManagement of a POM (BOM), with imports expanded. {(g,a): version}"""
    ck = (g, a, v)
    with _lock:
        if ck in _depmgmt_cache:
            return _depmgmt_cache[ck]
    out = {}
    try:
        _, pom = fetch_first(g, a, v, f"{a}-{v}.pom")
        root = ET.fromstring(pom)
        chain = _pom_chain(root)
        props = _props(chain, g, a, v)
        out = _collect_depmgmt(chain, props)
    except NotFound as e:
        warn(f"BOM {g}:{a}:{v} not found ({e})")
    with _lock:
        _depmgmt_cache[ck] = out
    return out


def _props(chain, g, a, v):
    props = {}
    for el in reversed(chain):              # parents first, child overrides
        ns = _ns(el)
        pr = el.find(ns + "properties")
        if pr is not None:
            for c in pr:
                props[c.tag.replace(ns, "")] = (c.text or "").strip()
        par = el.find(ns + "parent")
        if par is not None:
            for k in ("groupId", "artifactId", "version"):
                t = _text(par, ns, k)
                if t:
                    props[f"project.parent.{k}"] = t
                    props[f"parent.{k}"] = t
    for k in ("groupId", "artifactId", "version"):
        val = {"groupId": g, "artifactId": a, "version": v}[k]
        props[f"project.{k}"] = val
        props[f"pom.{k}"] = val
        props.setdefault(k, val)
    return props


def _collect_depmgmt(chain, props):
    managed = {}
    for el in reversed(chain):
        ns = _ns(el)
        dm = el.find(ns + "dependencyManagement")
        if dm is None:
            continue
        deps = dm.find(ns + "dependencies")
        if deps is None:
            continue
        for d in deps.findall(ns + "dependency"):
            dg = _interp(_text(d, ns, "groupId"), props)
            da = _interp(_text(d, ns, "artifactId"), props)
            dv = _interp(_text(d, ns, "version"), props)
            scope = _text(d, ns, "scope")
            if scope == "import" and dg and da and dv:
                for k, val in pom_depmgmt(dg, da, dv).items():
                    managed.setdefault(k, val)
            elif dg and da and dv:
                managed[(dg, da)] = dv
    return managed


def _parse_pom(pom, info):
    root = ET.fromstring(pom)
    chain = _pom_chain(root)
    ns0 = _ns(root)
    props = _props(chain, info.g, info.a, info.v)
    managed = _collect_depmgmt(chain, props)
    packaging = _interp(_text(root, ns0, "packaging"), props) or "jar"
    if packaging == "aar":
        info.kind = "aar"
        info.artifact_url = base_url(info.repo, info.g, info.a, info.v) + f"{info.a}-{info.v}.aar"
    elif packaging in ("jar", "bundle", "maven-plugin", "ejb"):
        info.kind = "jar"
        info.artifact_url = base_url(info.repo, info.g, info.a, info.v) + f"{info.a}-{info.v}.jar"
    else:
        info.kind = None                   # 'pom' etc.
    seen = set()
    for el in reversed(chain):
        ns = _ns(el)
        deps = el.find(ns + "dependencies")
        if deps is None:
            continue
        for d in deps.findall(ns + "dependency"):
            dg = _interp(_text(d, ns, "groupId"), props)
            da = _interp(_text(d, ns, "artifactId"), props)
            if not dg or not da:
                continue
            scope = _interp(_text(d, ns, "scope"), props) or "compile"
            optional = (_text(d, ns, "optional") or "false").lower() == "true"
            if scope not in ("compile", "runtime") or optional:
                continue
            dv = _interp(_text(d, ns, "version"), props) or managed.get((dg, da))
            if dv is None:
                warn(f"{info}: dependency {dg}:{da} has no version (unmanaged); skipped")
                continue
            version, ranged = normalize_version(dv)
            if (dg, da) in seen:
                continue
            seen.add((dg, da))
            info.deps.append(Dep(dg, da, version, ranged=ranged))
    # A POM's own dependencyManagement is NOT a constraint in Gradle when the POM
    # is an ordinary dependency; it only fills in versions above. Only platform()
    # imports become constraints.


# ----------------------------------------------------------------- build file
DEP_RE = re.compile(
    r"^\s*(implementation|api|runtimeOnly|debugImplementation|debugRuntimeOnly|releaseImplementation)"
    r"\s*\(\s*(platform\s*\(\s*)?\"([^\"]+)\"", re.M)


def parse_build_file(path, variant="debug"):
    src = open(path, encoding="utf-8").read()
    src = re.sub(r"//[^\n]*", "", src)      # commented-out dependencies don't count
    deps, platforms = [], []
    for m in DEP_RE.finditer(src):
        conf, is_platform, coord = m.group(1), bool(m.group(2)), m.group(3)
        if conf.startswith("release") and variant != "release":
            continue
        if conf.startswith("debug") and variant != "debug":
            continue
        parts = coord.split(":")
        if len(parts) < 2:
            continue
        g, a = parts[0], parts[1]
        v = parts[2] if len(parts) > 2 else None
        (platforms if is_platform else deps).append(Dep(g, a, v, platform=is_platform))
    return deps, platforms


# ----------------------------------------------------------------- resolution
class Resolution:
    def __init__(self):
        self.selected = {}          # key -> version
        self.nodes = {}             # key -> ModuleInfo (at the selected version)
        self.requests = defaultdict(lambda: defaultdict(set))     # key -> version -> {requester}
        self.constraints = defaultdict(lambda: defaultdict(set))  # key -> version -> {source}
        self.stricts = defaultdict(set)
        self.parent = {}            # key -> (parent_key or None, requested version)
        self.ranged = set()
        self.iterations = 0


def resolve(root_deps, root_platforms, forces, threads=8):
    res = Resolution()
    selected = dict(forces)
    for it in range(1, 25):
        res.iterations = it
        requests = defaultdict(lambda: defaultdict(set))
        constraints = defaultdict(lambda: defaultdict(set))
        stricts = defaultdict(set)
        parent = {}
        nodes = {}
        ranged = set()

        def add_platform(dep, source):
            for (pg, pa), pv in pom_depmgmt(dep.g, dep.a, dep.version).items():
                constraints[(pg, pa)][pv].add(source)

        for p in root_platforms:
            add_platform(p, "app(platform %s:%s:%s)" % (p.g, p.a, p.version))
        for d in root_deps:
            if d.version:
                requests[d.key][d.version].add("app")
            if d.strictly:
                stricts[d.key].add(d.strictly)
            parent.setdefault(d.key, (None, d.version))
            if d.ranged:
                ranged.add(d.key)

        def choose(key):
            if key in forces:
                return forces[key]
            if key in selected:
                return selected[key]
            return vmax(list(requests[key].keys()) + list(constraints[key].keys()))

        visited = set()
        level = [d.key for d in root_deps]
        while level:
            batch = []
            for key in level:
                if key in visited:
                    continue
                visited.add(key)
                ver = choose(key)
                if ver is None:
                    warn(f"{key[0]}:{key[1]} is requested without any version; skipped")
                    continue
                batch.append((key, ver))
            with ThreadPoolExecutor(threads) as ex:
                infos = list(ex.map(lambda kv: load_module(kv[0][0], kv[0][1], kv[1]), batch))
            nxt = []
            for (key, ver), info in zip(batch, infos):
                if info is None:
                    continue
                nodes[key] = info
                src = f"{info.g}:{info.a}:{info.v}"
                for d in info.deps:
                    if d.platform:
                        if d.version:
                            add_platform(d, src)
                        continue
                    if d.version:
                        requests[d.key][d.version].add(src)
                    if d.strictly:
                        stricts[d.key].add(d.strictly)
                    if d.ranged:
                        ranged.add(d.key)
                    parent.setdefault(d.key, (key, d.version))
                    if d.key not in visited:
                        nxt.append(d.key)
                for c in info.constraints:
                    if c.version:
                        constraints[c.key][c.version].add(src)
            level = list(dict.fromkeys(nxt))

        new_selected = {}
        for key in nodes:
            if key in forces:
                new_selected[key] = forces[key]
                continue
            new_selected[key] = vmax(list(requests[key].keys()) + list(constraints[key].keys()))
        stable = all(selected.get(k) == v for k, v in new_selected.items()) and \
            all(k in new_selected for k in selected if k not in forces)
        selected = new_selected
        res.selected, res.nodes = selected, nodes
        res.requests, res.constraints, res.stricts = requests, constraints, stricts
        res.parent, res.ranged = parent, ranged
        if stable:
            break
    else:
        warn("resolution did not converge in 24 iterations; results may be off")
    return res


# ----------------------------------------------------------------- AAR inspection via HTTP ranges
class HttpRangeFile:
    """Seekable read-only view of a remote file, fetched in 256 KB pieces."""
    CHUNK = 256 * 1024

    def __init__(self, url):
        self.url = url
        self.pos = 0
        self.chunks = []
        req = urllib.request.Request(url, method="HEAD", headers=UA)
        with urllib.request.urlopen(req, timeout=90) as r:
            self.size = int(r.headers["Content-Length"])

    def seekable(self):
        return True

    def readable(self):
        return True

    def seek(self, off, whence=0):
        if whence == 0:
            self.pos = off
        elif whence == 1:
            self.pos += off
        else:
            self.pos = self.size + off
        return self.pos

    def tell(self):
        return self.pos

    def _fetch(self, start, end):
        req = urllib.request.Request(self.url, headers={**UA, "Range": f"bytes={start}-{end}"})
        with urllib.request.urlopen(req, timeout=90) as r:
            if r.status != 206:
                raise RuntimeError("server ignored Range header")
            return r.read()

    def read(self, n=-1):
        if n is None or n < 0:
            n = self.size - self.pos
        end = min(self.size, self.pos + n)
        out = bytearray()
        pos = self.pos
        while pos < end:
            for (s, data) in self.chunks:
                if s <= pos < s + len(data):
                    take = data[pos - s: pos - s + (end - pos)]
                    out += take
                    pos += len(take)
                    break
            else:
                # for the tail, fetch backwards so the central directory usually
                # comes along for free
                if pos > self.size - 64 * 1024:
                    fs = max(0, self.size - self.CHUNK)
                else:
                    fs = pos
                fe = min(self.size - 1, max(fs + self.CHUNK, end) - 1)
                data = self._fetch(fs, fe)
                if not data:
                    break
                self.chunks.append((fs, data))
        self.pos = pos
        return bytes(out)

    def close(self):
        pass


def aar_facts(url):
    """{'minCompileSdk','minAgp','minSdk','hasMeta','error'} for an AAR, cached."""
    p = _cache_path(url + "#facts")
    if os.path.exists(p):
        return json.loads(open(p, encoding="utf-8").read())
    facts = {"minCompileSdk": None, "minAgp": None, "minSdk": None, "hasMeta": False, "error": None}
    try:
        try:
            z = zipfile.ZipFile(HttpRangeFile(url))
        except Exception:
            z = zipfile.ZipFile(io.BytesIO(fetch(url, cache=False)))   # no range support
        names = set(z.namelist())
        meta = "META-INF/com/android/build/gradle/aar-metadata.properties"
        if meta in names:
            facts["hasMeta"] = True
            for line in z.read(meta).decode("utf-8", "replace").splitlines():
                if "=" in line and not line.startswith("#"):
                    k, v = line.split("=", 1)
                    k, v = k.strip(), v.strip()
                    if k == "minCompileSdk":
                        facts["minCompileSdk"] = int(re.sub(r"\D", "", v) or 0)
                    elif k == "minAndroidGradlePluginVersion":
                        facts["minAgp"] = v
        if "AndroidManifest.xml" in names:
            man = z.read("AndroidManifest.xml").decode("utf-8", "replace")
            m = re.search(r'android:minSdkVersion="(\d+)"', man)
            if m:
                facts["minSdk"] = int(m.group(1))
    except Exception as e:
        facts["error"] = f"{type(e).__name__}: {e}"
    _store(p, json.dumps(facts).encode("utf-8"))
    return facts


# ----------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", default="app/build.gradle.kts")
    ap.add_argument("--sdk", type=int, default=36, help="project compileSdk")
    ap.add_argument("--agp", default="8.13.2", help="project AGP version")
    ap.add_argument("--minsdk", type=int, default=29, help="project minSdk")
    ap.add_argument("--variant", default="debug")
    ap.add_argument("--set", action="append", default=[], metavar="G:A=V")
    ap.add_argument("--force", action="append", default=[], metavar="G:A=V")
    ap.add_argument("--all", action="store_true", help="list jars too")
    ap.add_argument("--quiet", action="store_true", help="only the verdict and failures")
    ap.add_argument("--threads", type=int, default=8)
    args = ap.parse_args()

    deps, platforms = parse_build_file(args.build, args.variant)
    for s in args.set:
        coord, v = s.split("=", 1)
        g, a = coord.split(":")
        for d in deps:
            if d.key == (g, a):
                d.version = v
                break
        else:
            deps.append(Dep(g, a, v))
    forces = {}
    for s in args.force:
        coord, v = s.split("=", 1)
        g, a = coord.split(":")
        forces[(g, a)] = v

    print(f"direct: {len(deps)} dependencies, {len(platforms)} platform(s) from {args.build} ({args.variant})")
    for p in platforms:
        print(f"  platform {p}")
    for d in deps:
        print(f"  {d}")
    if args.set:
        print(f"  overrides: {', '.join(args.set)}")
    if forces:
        print(f"  forced: {', '.join(f'{k[0]}:{k[1]}={v}' for k, v in forces.items())}")

    res = resolve(deps, platforms, forces, threads=args.threads)
    keys = sorted(res.nodes, key=lambda k: (k[0], k[1]))
    print(f"\nresolved {len(keys)} modules in {res.iterations} iteration(s)")

    for d in deps:
        sel = res.selected.get(d.key)
        if d.version and sel and sel != d.version:
            print(f"  note: {d.g}:{d.a} declared {d.version} but resolves to {sel}")

    aars = [k for k in keys if res.nodes[k].kind == "aar" and res.nodes[k].artifact_url]
    with ThreadPoolExecutor(args.threads) as ex:
        facts = dict(zip(aars, ex.map(lambda k: aar_facts(res.nodes[k].artifact_url), aars)))

    agp_ceiling = agp_tuple(args.agp)
    failures = []
    rows = []
    for k in keys:
        info = res.nodes[k]
        v = res.selected.get(k, info.v)
        if info.kind == "aar" and info.artifact_url:
            f = facts[k]
            probs = []
            if f.get("error"):
                probs.append(f"inspect failed: {f['error']}")
            if f["minCompileSdk"] and f["minCompileSdk"] > args.sdk:
                probs.append(f"needs compileSdk {f['minCompileSdk']}")
            if f["minAgp"] and agp_tuple(f["minAgp"]) > agp_ceiling:
                probs.append(f"needs AGP {f['minAgp']}")
            if f["minSdk"] and f["minSdk"] > args.minsdk:
                probs.append(f"needs minSdk {f['minSdk']}")
            verdict = "FAIL" if probs else "ok"
            if probs:
                failures.append((k, v, probs))
            rows.append((verdict, f"{k[0]}:{k[1]}:{v}", "aar",
                         f"compileSdk>={f['minCompileSdk']}" if f["minCompileSdk"] else "-",
                         f"agp>={f['minAgp']}" if f["minAgp"] else "-",
                         f"minSdk {f['minSdk']}" if f["minSdk"] else "-",
                         "" if f["hasMeta"] or f.get("error") else " (no aar-metadata)"))
        else:
            rows.append(("", f"{k[0]}:{k[1]}:{v}", info.kind or "pom", "", "", "", ""))

    if not args.quiet:
        print(f"\n{'':4s} {'module':78s} {'kind':4s} {'compileSdk':14s} {'agp':12s} {'minSdk':9s}")
        print("-" * 126)
        for r in rows:
            if r[2] != "aar" and not args.all:
                continue
            print(f"{r[0]:4s} {r[1]:78s} {r[2]:4s} {r[3]:14s} {r[4]:12s} {r[5]:9s}{r[6]}")

    def path_to_root(key):
        chain = []
        seen = set()
        while key is not None and key not in seen:
            seen.add(key)
            v = res.selected.get(key, "?")
            par = res.parent.get(key)
            asked = f" (asked for {par[1]})" if par and par[1] and par[1] != v else ""
            chain.append(f"{key[0]}:{key[1]}:{v}{asked}")
            key = par[0] if par else None
        return " <- ".join(chain) + " <- app"

    print()
    print(f"ceiling: compileSdk <= {args.sdk}, AGP <= {args.agp}, minSdk <= {args.minsdk}")
    print(f"AARs checked: {len(aars)} of {len(keys)} modules")
    if res.ranged:
        print("version ranges taken at lower bound: " + ", ".join(f"{g}:{a}" for g, a in sorted(res.ranged)))
    if failures:
        print(f"\nFAIL: {len(failures)} artifact(s) would be rejected by checkDebugAarMetadata\n")
        for k, v, probs in failures:
            print(f"  {k[0]}:{k[1]}:{v}  --  {'; '.join(probs)}")
            print(f"     path: {path_to_root(k)}")
            reqs = res.requests.get(k, {})
            cons = res.constraints.get(k, {})
            for ver in sorted(reqs, key=vkey, reverse=True):
                print(f"     requested {ver:10s} by {', '.join(sorted(reqs[ver]))}")
            for ver in sorted(cons, key=vkey, reverse=True):
                srcs = sorted(cons[ver])
                more = f" (+{len(srcs) - 4} more)" if len(srcs) > 4 else ""
                print(f"     constrained {ver:8s} by {', '.join(srcs[:4])}{more}")
            print()
    else:
        print("\nOK: every AAR in the resolved graph is within the ceiling")

    if WARNINGS:
        print(f"\n{len(WARNINGS)} warning(s):")
        for w in WARNINGS[:40]:
            print(f"  - {w}")
        if len(WARNINGS) > 40:
            print(f"  ... {len(WARNINGS) - 40} more")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
