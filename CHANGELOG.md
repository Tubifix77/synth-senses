# Changelog

All notable changes to this project are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is
[SemVer](https://semver.org/spec/v2.0.0.html).

The percept wire format carries its own `schema` integer, bumped independently of
the app version whenever the shape changes in a way that breaks receivers.

## [Unreleased]

The app changes here, so the APK is no longer the 0.3.0 one. **`minSdk` drops
to 28**, and **the percept schema goes to 3**: `radio.ble_count` and
`radio.ble_named` can now be null, meaning the phone could not look. A v2
receiver that tolerates a null there parses these percepts unchanged; one that
assumes a number will not. Nothing else on the wire changed shape.

### Added

- **`status` counts the samples hearing gave up to a call**, as
  `mic_call_stand_downs`. The first ring test on the P20 rang normally but
  could not be read off the percept log, where a skipped sample is only a
  missing one; read the count before and after a call instead.
- **A blind WiFi scan claims no place.** From 2026-10-24 the P20's WiFi scans
  came back empty for five days while it stayed connected to its home network,
  so place learning had BLE alone, and a phone that never moved minted about
  1,400 new places and published a different `place` to Potluck every few
  samples. A scan that lists nothing while connected is now treated as blind:
  `radio.place_id` is null rather than new.
- **An old fix is not written to Potluck as current.** The same five days the
  phone received no location fixes and sent its last one, honestly aged by
  `gps.age_s`, which the bridge never read: Potluck showed a five-day-old
  position as live. `lat` and `lon` are now written only from a fix at most
  15 minutes old, so a stopped feed ages into STALE.
- **The phone's PC side is one program.** `tools/synth_senses_service.py`
  runs the receiver, the bridge and the port together, starts any that stops
  (at most once in 30 s), and stops them all when it stops. It is meant to be
  started by Potluck's tray beside the hub, so the phone is served whenever
  Potluck is. Until now the three were started by hand and stopped whenever
  the terminal or Claude session holding them did, which left the phone STALE
  in Potluck three times in a week. A second copy exits at once (code 3)
  instead of running a second bridge. On Windows the programs are held in a
  job object, so killing the service, which is what a tray does, cannot leave
  an orphaned bridge writing beside the next one. A test kills a parent with
  and without that guard to show the difference. Output goes to rolling logs
  in `logs/`, and lines appended to `logs/commands.jsonl` reach the phone.
- **Broadcast names arrive as text.** A headset near the P20 advertised its
  name with a trailing NUL, which went into `radio.ble_named` and the narration
  as `\u0000`. Bluetooth names and WiFi SSIDs now have control characters
  dropped and are trimmed, and a name made only of padding is null.
- **The ears listen to the room, on every phone.** Hearing asked for the
  `UNPROCESSED` microphone input unconditionally, and Android documents that a
  phone without it "behaves like DEFAULT" — silently, with no error — so the
  fallback to the plain mic in a `catch` never ran, and on such a phone the
  ears were on the ordinary input, processed for a mouth close to the mic.
  The input is now chosen from the phone's own declaration
  (`PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`, CDD 5.11): `UNPROCESSED` where
  declared, otherwise `VOICE_RECOGNITION`, on which the CDD requires noise
  reduction and automatic gain control off by default (5.4.2). Never the
  speakerphone input, which suppresses background on purpose and belongs to a
  call. `status` reports `mic_source`. On a phone that changes input,
  `hearing.level_db` may shift, since the old input may have applied gain. Prompted by
  Tue asking whether "speakerphone mode" would hear the TV better.
- **A sense switched off is no longer replayed into Potluck as current.** The
  bridge refreshed every value it had ever written, from its last written
  value, so nothing went STALE once the phone stopped reporting it: a sense
  switched off kept its last reading as GOOD with a small age, and location
  switched off would have refreshed its last fix indefinitely. Only values the
  phone is still reporting are refreshed now — a healthy phone re-reports
  every live value each heartbeat, inside the window — so a stopped sense ages
  into STALE, and the port withholds STALE, answering 503 within minutes.
  Found by checking a claim made to the AI-Consciousness session rather than
  repeating it; the claim was wrong.
- **Hearing reports the interval, not one second.** In a steady scene the
  phone habituates and sends a percept every two minutes, and each carried the
  sound class of its own 975 ms window; every other sample's classification was
  discarded. A companion reading Potluck with the TV on was told "quiet" or
  "other" almost every time, while the classifier's top sound across the same
  samples was Speech 18 of 60. Every sample is now tallied into a nullable
  `hearing.recent`, and the bridge publishes the dominant category over the
  interval and its energy-mean loudness. No schema change, and habituation is
  untouched. Found when the AI-Consciousness session asked why a loud TV
  never reached its companion as speech.
- **`tools/potluck_port.py`: the phone's senses, read back off Potluck and
  served to a companion.** Built with the ai-consciousness session to fit its
  read-only sensor contract: five loopback GET paths, each a JSON object with
  `age_s`, ordinals turned back into words, 32-bit float noise trimmed before
  it can reach a signed memory. **503** is an honest absence; **502** is the
  hub being unreachable, which the companion counts as blindness. There is no
  location path at all, so coordinates cannot enter that memory.
- **The phone can be steered from code.** Commands go into the receiver as
  JSON lines on stdin, the mirror of percepts leaving on stdout, so the
  sensing side is an ordinary bidirectional filter — no socket, no port, no
  second protocol. The phone's `ack`, `status`, `places` and `hello` replies
  come back on stdout beside the percepts, told apart by one rule: **a reply
  always carries `type`; a percept never does.** A command outside the
  vocabulary is refused at the receiver and never reaches the phone, as is a
  malformed line or one sent with nothing connected — each answers
  `{"type": "error", ...}` rather than vanishing. This was the one item
  `docs/ROADMAP.md` listed as blocked on nothing, and the prerequisite for
  every version of the bridge.
- **`status` reports what the phone actually is.** `device` gives model,
  manufacturer and Android version; `sensors` lists each sensor the body
  sense uses, whether it is physically present, and the hardware's own name
  for it. The app degrades silently on missing hardware, which is right at
  runtime and useless when the hardware is the unknown — and reading logcat
  needs an `adb` this project's PC side does not have.
- `senses/Vocabulary.kt` — the closed label lists in a fixed order, because a
  consumer that cannot carry strings carries the index instead. That makes
  their order a wire contract no percept ever shows. **Append, never insert.**
- [`tools/potluck_bridge.py`](tools/potluck_bridge.py) — publishes percepts
  into a Potluck cell as ten numbers. Writes on change with a per-value
  epsilon, refreshes at half of `stale_ms` so a quiet room never reads STALE,
  coalesces newest-wins rather than queueing, stays under the hub's write
  ceiling, and pins the hub's certificate so the pairing token only ever
  travels to the hub that issued it.
- [`tools/apk_facts.py`](tools/apk_facts.py) — reads a built APK's binary
  manifest with no `aapt` and no Android SDK. **CI gates on it**, so the fact
  table in `docs/VALIDATION.md` cannot quietly go stale the way it did.
- The emulator runs instrumented tests at **API 28 and API 36** — `minSdk` and
  `targetSdk`, the two ends of the range the app is developed for. Until this
  existed the floor was compiled and never once started.

- `tools/digest.py` — turns a percept log into a report. Built for reading a
  hardware test: it breaks `accel_rms` down per motion state, which is the
  measurement `BodySensor`'s guessed thresholds need, reports battery movement
  as points per hour, flags gaps over three minutes, and lists sensors that
  never reported. **OCR text and transcripts are redacted by default**, since
  its output is meant to be safe to paste somewhere.
- [`docs/INTEGRATION.md`](docs/INTEGRATION.md) — the spec for whoever builds
  the consuming side, written to be coded against without reading the Kotlin.
  It documents only what runs. When it was written that meant saying plainly
  that the return path was unreachable from code; it now documents that path
  instead, and still says curated reports do not exist yet.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — what happens next and in what order.
  A phone first, then the bridge to the separate machine-consciousness project.
  Records where the bridge should live and which half of it is blocked on
  nothing.
- Three more stdlib test suites in CI: `test_receiver_ws.py` for the WebSocket
  transport and the stdout contract, `test_digest.py` for the digest and its
  redaction, and `test_docs.py`, which checks the facts in `INTEGRATION.md`
  against the source so a spec someone codes against cannot quietly rot.
- **The receiver announces itself.** A small UDP datagram every 2 s on port
  8078, to the limited broadcast and to each of its own `/24`s, saying where it
  is. The app listens for it behind a **Find receiver** button — only when
  pressed, because a socket open at launch is a thing running that nobody
  asked for — and fills the Endpoint field in. The packet carries no percept,
  command or reply, and never touches stdout, which `test_receiver_ws.py`
  pins alongside the payload's shape. Whatever it claims goes through the same
  URL rules as hand-typed input, so a stranger on the LAN cannot put an
  arbitrary string in front of the user. `--no-beacon` turns it off.
  Documented in `docs/PROTOCOL.md`, with the port checked against `Endpoint.kt`
  and `receiver.py` on every push.

### Changed

- **The Endpoint field fills itself in as far as the phone can.** From the
  first setup on a real phone: the field was empty under a grey
  `ws://192.168.1.20:8077/link` — an example, on a different subnet from the
  phone's. It reads as a value that is already there, so Start was pressed
  with nothing in the field; and because it could not be accepted or edited,
  all thirty characters then had to be typed by thumb. Now the field opens
  holding the phone's own `/24` (read from `ConnectivityManager`'s
  `LinkProperties`, not the deprecated `WifiManager.getConnectionInfo`), the
  scheme, port and `/link` path are filled in around whatever is typed, and a
  bare `7` becomes `ws://192.168.0.7:8077/link`. The supporting line says what
  will actually be dialled before Start is pressed. The rules are a pure object,
  `senses/Endpoint.kt`, unit tested on the JVM for the same reason
  `Habituation` takes a `File`.
- **Start is disabled until there is somewhere to start.** Pressing it with an
  empty field made `Link.connect` return at once with `OFFLINE` and
  "no endpoint set", which from the panel is indistinguishable from an
  ordinary offline link.

- **The receiver is composable.** Percepts leave on stdout as one JSON object
  per line and everything written for a person leaves on stderr, so downstream
  is a pipe rather than an edit to `handle_percept()`. JSON lines switch on
  automatically when stdout is not a terminal, so running it bare still gives
  the readable live view; `--jsonl` / `--no-jsonl` force it, `--quiet` and
  `--pretty` control the human rendering.
- The websocket's `"type": "percept"` envelope is stripped before a percept is
  logged or streamed, so every line is a percept regardless of whether it
  arrived over the websocket, in a backlog flush, or by HTTP POST. The JSONL
  log was previously inconsistent about this.
- The command console only starts when stdin is a terminal. Otherwise it would
  consume whatever is being piped in.
- The receiver prints an address a phone can actually dial. It used to print
  its bind address, `ws://0.0.0.0:8077/link`, which nobody can type into a
  phone, at exactly the moment someone is standing there trying to. It now
  reads the routing table, shows that address first, lists virtual adapters
  after it, and names the host firewall as the usual reason a connection
  fails.

- **`minSdk` is 28**, down from 29, so an Android 9 phone can run this.
  Nothing in the dependency graph objected — the highest `minSdk` anything
  resolves to is 24 — confirmed with `tools/transitive_sweep.py --minsdk 28`
  across the whole resolved graph rather than from the direct list.
- **The ears stand aside for the telephone.** `AudioSensor` refuses to open
  the microphone whenever `AudioManager.mode` is anything but `MODE_NORMAL` —
  from the first ring rather than from the moment someone answers, and
  covering call screening and the redirect modes without having heard of them.
  One of the phones this runs on is a household's emergency line; losing a
  sound label is always the right trade.
- Every tool that prints percept text forces UTF-8 on its streams.

### Fixed

- **A one-entry scan claims no place.** Lying still on 2026-10-29, the P20's
  WiFi scans kept shrinking to the home network alone, and each such sample
  with no BLE in range minted a new place: 17 in 90 minutes, none of which
  any later scan could match, since a match needs two shared entries.
  `place_id` is now null for a fingerprint that small. This is half of that
  night's churn, not all of it: scans of two networks minted another 9, for
  reasons the percept log cannot show, and a place founded on such a scan
  went on to become the shelf's id for the rest of the day.
- **A `set` that worked says so.** The command's own `cmd` field was counted
  as an unknown setting, so every remote `set` came back `ok: false` with
  "not a setting: cmd" after applying everything it was given. Seen on the
  P20 toggling `location`.
- **A cell signal of −44 dBm is no longer published as a reading.** 124 of
  the Xiaomi's 323 readings were exactly −44 — the top of the LTE RSRP range —
  on cells that otherwise read −102 to −112; it is that modem's placeholder for
  "no measurement". LTE and NR at −44 now publish null, and so does Android's
  `UNAVAILABLE` sentinel, which had no filter and would have gone out as
  2147483647 dBm.
- **Location is sensed rather than borrowed, and says how old it is.** The
  app never requested a fix: it read `getLastKnownLocation`, whatever another
  app had last asked for, and threw the timestamp away. The P20 published one
  fix unchanged for 20.75 hours across 895 percepts, Potluck included. It now
  requests updates from the network provider and GPS about once a minute while
  `location` is on, and adds `gps.age_s` — a nullable field, so no schema
  change. The gps block is documented in `PROTOCOL.md` for the first time.
- **No compass bearing is published when the field is not Earth's.** The
  P20's resting spot by its charger sits in a 351 µT field — Earth's is 22–67
  anywhere on the surface — so for 802 of its 901 samples `heading_deg` pointed
  at a local magnet, and the narration said "facing south-west" 579 times. It
  is now null outside 20–75 µT; `magnetic_ut` still says why.
- **The phone no longer describes what it "sees" in the dark.** On a frame
  with brightness 0.00 the image labeller returned Sky, Monochrome and Space
  for all 749 such frames, and the narration repeated them — "I can see sky,
  monochrome, space" in a pitch-dark room. Below brightness 0.10 those three
  words are the labeller's answer to any black image, measured across both
  phones, so vision now publishes empty labels, objects and text there, keeps
  `brightness`, and skips running three models on nothing.
- **BLE was blind whenever the screen was off, and reported that as an empty
  room.** Since Android 8.1 an unfiltered BLE scan returns nothing with the
  screen off, and the scanner passed no filter. With the screen off the P20
  heard BLE in 3 of 784 samples and the Xiaomi in 6 of 115, against 72% and
  77% with it on — and a phone left to run untouched is screen-off nearly all
  the time. Every one of those blind samples went out as `ble_count: 0`,
  including to Potluck. The scan now passes one empty `ScanFilter`, which
  Android 9 through 14 treat as filtered (read from AOSP's `ScanManager` for
  each release; 15 closed it). Separately, Android refuses a sixth scan start
  inside 30 s, roughly one sample in six at the 5 s default, and that refusal
  was also published as zero. Both are now `null`, as is BLE switched off or
  denied — hence schema 3.
- **`charging` means plugged in, as documented.** It came from
  `BatteryManager.isCharging`, which follows vendor battery protection: the P20
  holds itself at 81–82% on a charger in a regular ~88-minute top-up cycle, and
  the flag followed the cycle, reading `false` for 182 of 299 samples taken on
  the charger. It now comes from `EXTRA_PLUGGED`; whether current is flowing in
  right now is the sign of `current_ma`.
- **`current_ma` is no longer 1000x wrong on Huawei.** Android documents
  `BATTERY_PROPERTY_CURRENT_NOW` as microamps and the code divided by 1000
  accordingly; Huawei reports milliamps. Across a night on a wall charger the
  P20 published a range of −0.64 to +1.01 mA — a phone drawing half a
  milliamp while charging, which is impossible and looks entirely reasonable.
  The real figures were about −640 and +1010 mA. `CurrentScale` now works the
  unit out from the readings themselves, since the two scales are a hundredfold
  apart for any real phone, and reports **null** until it knows rather than
  publishing a number that might be out by three orders of magnitude.
- **The control panel can no longer misreport what the phone is doing.** Every
  sense switch was `remember { mutableStateOf(prefs.X) }` — read once when the
  screen composed and never again — while only the link status was reactive. A
  sense changed over the link was obeyed by the service and invisible to the
  panel, which kept drawing the value it had captured. Observed: speech
  switched off remotely, the beeping stopped, and the toggle still read ON.
  That direction is benign; the other is not, since the panel is the only
  on-device way to see whether the microphone is in use. The service now
  announces a remote change and the panel re-reads. Only remote writes count,
  so dragging a slider is not re-initialised under the finger.
- **`set` says what it actually did.** The reply was an unconditional
  `"settings applied"`, so a call containing nothing but a typo was
  acknowledged as a success — and `location` and `voice` had no branch at all,
  so switching the GPS on over the link was accepted and discarded. The reply
  now names each key applied, lists anything unrecognised, and reports `ok:
  false` when a key was not a setting.
- **`still` is decided per device instead of by a constant that fitted one
  phone.** A Huawei P20 Lite resting untouched on a charger reports
  `accel_rms` 0.17–0.18; a Xiaomi 14T Pro idles at 0.04–0.05. The threshold
  was an absolute 0.08, so the Huawei reported `handled` for 290 of 303
  samples across a night in which nobody touched it — and `motion` is a
  published value, so Potluck showed the phone being handled until morning.
  `MotionFloor` now learns the resting floor from the median of recent
  readings, capped so a phone that is never put down cannot learn that motion
  is rest, and floored at the old 0.08 so the change can only widen `still`
  and never narrow it. Rotation stays absolute, because a gyroscope reads near
  zero at rest on every device. Measured against the recorded logs of both
  phones: 100% on known-still samples where the old constant scored 4.3%, with
  every known-moving sample still rejected.
- **`classifyMotion` is unit-testable for the first time.** It was private
  inside a class that needs a `SensorManager`, which is why the most
  device-dependent decision in the app shipped unverified. It is now a pure
  function in `MotionFloor.kt` with `MotionFloorTest` pinning both phones'
  measured resting bands.
- **The foreground notification is no longer rebuilt on every sample.** It
  was re-posted with the latest narration each cycle, and the call sat *above*
  the habituation return, so it fired even for samples that were never sent.
  It now carries only the enabled senses and the link state, is marked
  `setSilent(true)`, and is re-posted only when that text actually differs —
  so in a steady state it is posted once and never again.

  **Correction:** this shipped under a commit message saying it stopped the
  P20 chiming. It did not. The chime was EMUI announcing mic-open and
  mic-close as an accessibility feature, which fires for any microphone use
  and is disabled in the phone's own accessibility settings. The notification
  change stands on its own merits — rebuilding a notification with fresh prose
  twelve times a minute is wrong regardless — but it was not the cause and the
  message overclaimed.
- **One unprovisioned value no longer starves every other value.** A 404
  returns a 60-second hold, and that hold was applied to the shared rate
  budget rather than to the path that failed, while the drain loop ended the
  whole pass on any failure. Since `Pending.take()` is oldest-written-first
  and a never-written value sorts to the head of the queue by construction, an
  unprovisioned `lat` was taken first on every pass, failed, and stood the
  entire writer down — about one write a minute, with none of the other eleven
  values landing. Holds are now per path and a failure no longer ends the
  pass; the rate budget keeps only the program-wide limit, which is the one
  thing a 429 genuinely is. Found by the Potluck Desk session watching the hub
  from the other side, not by any test here.
- **A momentarily thin scan no longer mints a duplicate of the room you are
  standing in.** Place recognition measured only how much of a place was
  visible, never how much of what was visible belonged to the place, so a
  subset of the current room looked exactly like a different room. On hardware
  one sample that saw 10 of its usual 18 access points dropped coverage to
  0.49 against a bar of 0.50 and minted a permanent second id for a room that
  already had 870 visits recorded against it. Minting now also requires that
  what is visible is *not* mostly accounted for by a place already known, and
  a scan too thin to act on no longer counts as a visit — which previously
  diluted every anchor's persistence and slowly dissolved the room it had just
  failed to recognise. A single stray device shares nothing with the room and
  is still correctly new.
- **The reported place no longer flips between two ids while the phone lies
  still.** Coverage is capped at 1, so every place whose visible fraction
  exceeds its own mean persistence scores exactly 1.00, and the selection loop
  broke that tie with a strict `>` — which meant by whichever place happened
  to be learned first. Two places covering one room therefore alternated, 114
  times across 531 samples, with GPS identical throughout. Ties are now broken
  on the weight of corroborating evidence, so the better-established place
  wins and the answer does not oscillate.
- **`Link.lastDetail` was on screen and still invisible.** The panel has always
  shown it, as the fifth grey line in a stack of grey statistics, prefixed
  "uplink:" like one of them — and for the first few seconds it read "—",
  because nothing published it until the first sample five seconds later. It
  now goes out the moment `connect()` returns, and is coloured as an error
  whenever the service is running and the link is neither `OPEN` nor `HTTP`.
- **A malformed endpoint could take the service down.** okhttp throws rather
  than returning null on a URL it cannot parse, and that call sat outside the
  try in both `openSocket` and `sendHttp` — in the first case on the service's
  start path. A half-typed address now reports itself as one.
- **A BLE scan below API 31 never asked for the permission it needs.**
  `BLUETOOTH_SCAN` does not exist before 31, where a scan instead requires
  `ACCESS_FINE_LOCATION` — and that was only requested when wifi, cell or GPS
  were switched on, all of which default to off, while BLE defaults to on. So
  on any phone from API 28 to 30 with stock settings the scan returned
  nothing, `ble_count` was a permanent zero, the place fingerprint was never
  non-empty, and `PlaceMemory.recognise` was not reached once. Place learning
  — redesigned three times and validated across 2000 seeds — could not work at
  all there. Nothing threw; it quietly did nothing. The decision now lives in
  `senses/Permissions.kt` as a pure function, tested at every API level the
  app supports.
- **`CellInfoNr` is API 29**, and was referenced unguarded. Harmless at
  `minSdk` 29 and a lint error below it.
- **The launch test restated the permission list instead of deriving it**, so
  it stopped granting what the app asks for the moment the BLE fix landed, and
  both cases failed on Android 9 with "never becomes RESUMED" — which reads
  like a crash and was not one. It had been wrong for API 28, 29 and 30 ever
  since, and nothing noticed because the only level the emulator ran was 35.
- **The stream died on any character Windows cannot spell.** A narration, an
  OCR line or a transcript can hold anything; on Windows a pipe defaults to
  cp1252, so the feed broke on the first such word — in `digest.py` too, which
  prints exactly that text. Never reproduced in CI, which is Linux and UTF-8
  already.
- **`motion` was documented with a value it cannot return.** `PROTOCOL.md`
  listed an `unknown` that `classifyMotion` has no branch for; there are four
  values, not five. Both documents and the ordinals sent to a consumer are
  corrected, and a test now fails on a reorder.
- **A rendering error could break the transport.** A percept with no `trigger`
  made the human renderer raise while formatting; the exception escaped to the
  connection handler and the client received no HTTP response at all, which a
  phone reads as a failed POST and re-spools forever. The renderer no longer
  assumes fields are present or well-typed, and `deliver()` isolates it, so
  logging, the stdout stream and the response never depend on the
  pretty-printer.

## [0.3.0] — 2026-09-18

The first version that compiles, and the first whose tests have ever run.

Everything before this was written without an Android SDK to hand, so "it
builds" was an assumption. It is now a fact that CI re-checks on every push,
along with a debug APK, Android Lint, 71 JVM unit tests and two standard
library test suites for the tooling.

No wire-format change: `schema` stays at 2, and a v0.2 receiver will parse
these percepts unchanged.

### Fixed

- **`PlaceMemory` could not recognise a place twice — at all, on any input.**
  A place created moments ago has every anchor at `seen = 1`, the persistence
  filter demands 2, so its expected weight was zero, its coverage against any
  scan was zero, and it never received the second observation that would have
  let it mature. Eighteen visits to one room produced eighteen rooms. The
  metric itself was sound and validated; the prototype trained places by
  calling `observe()` directly and so never exercised the path a phone takes.
  A place is now *learning* for its first six visits: no persistence filter, a
  lower recognition bar to offset the passers-by still inflating its
  denominator, and every recognition teaches it. Settled places are unchanged.
  See [docs/VALIDATION.md](docs/VALIDATION.md).
- `VisionSensor.bind()` awaited a `ListenableFuture` with the Play Services
  `await`, which does not apply to it. Uses CameraX's own `awaitInstance` now.
  This was the only real error in the Kotlin, and it was in code nobody had
  flagged as risky.
- `TempoSensorTest` handed `Int` expressions to the `Double` overload of
  `assertEquals`, so the test sources did not compile.
- `app/src/main/assets/README.md` was being packaged into the APK and shipped
  to devices. Found by opening the artifact CI produces.

### Added

- **Tests for the invariants that had none.** Token bucketing, which the whole
  attention model rests on and whose failure would have been silent; sense
  blocks serialising as explicit nulls rather than fabricated values; the
  FNV-1a identifier hash, pinned to independently computed values; the bounded
  backlog; inbound command parsing; the narrator; and the full wire shape, so
  `docs/PROTOCOL.md` cannot drift from the code unnoticed.
- `tools/transitive_sweep.py` — resolves the whole runtime dependency graph the
  way Gradle does and reads each AAR's `aar-metadata.properties`, so a
  `checkDebugAarMetadata` rejection is answerable in seconds instead of a
  four-minute CI cycle. It reads the ceiling out of the build files, so it
  cannot go stale. Pure stdlib.
- `tools/test_receiver_ws.py` — the WebSocket transport, which is the primary
  one and had never been tested automatically. Speaks RFC 6455 as a real
  client, with the handshake asserted against the spec's own published example.
- `tools/test_transitive_sweep.py` — the sweep's version ordering and ceiling
  detection, since a wrong answer there would be quiet rather than loud.
- `Spool` takes a `File` with a `Context` convenience constructor, and
  `shortHash` is a top-level internal function, both so a plain JVM test can
  reach them. The arrangement `Habituation` and `PlaceMemory` already used.

### Changed

- **AGP 9.4.1, Gradle 9.7.1, compileSdk 37, Kotlin 2.4.20.** Four artifacts had
  reached releases declaring `minCompileSdk=37` and were each pinned below
  their current version by that one wall, with the gap widening on every
  dependency run. AGP 9 compiles Kotlin itself, so `org.jetbrains.kotlin.android`
  is gone; it ships Kotlin 2.2.10, so the root `buildscript` raises that to the
  2.4.20 this project uses.
- Dependencies freed by the move: `core-ktx` 1.19.0, `lifecycle` 2.11.0,
  Compose BOM 2026.09.00, okhttp 5.5.0. The okhttp bump was never an API
  problem, only a metadata one.
- MediaPipe `tasks-audio` 0.10.35 to 1.0.0. Verified rather than assumed: it
  ships the same classes, and everything `AudioSensor.kt` names is present in
  the `tasks-core` 1.0.0 it depends on. Three documents had predicted this
  would be the breakage. It was the cleanest bump of the batch.
- CI actions to `checkout` v7, `setup-java` v6, `setup-python` v7,
  `upload-artifact` v7, and `gradle/actions` v6 with `cache-provider: 'basic'`.
  v6 defaults to a proprietary caching component whose use means accepting
  Gradle's commercial terms; `basic` is the open-source path over the GitHub
  Actions cache.
- Status claims across `README.md`, `docs/VALIDATION.md`, `CONTRIBUTING.md` and
  `CLAUDE.md` corrected once they became untrue, including the repeated
  prediction that MediaPipe would be the thing that broke.
- `app/src/main/assets/README.md` now gives a direct, scriptable YAMNet URL
  with size and checksum instead of sending people to Kaggle.

### Not changed, deliberately

- **`targetSdk` stays at 36** while compileSdk moves to 37. compileSdk changes
  what the app is compiled against; targetSdk changes how Android behaves
  towards it at runtime, and nothing here has run on a phone.
- **Kotlin stays at 2.4.20** rather than falling back to the 2.2.10 AGP 9
  supplies.
- **The debug APK is ~178 MiB** and ships four ABIs. That is ML Kit and
  MediaPipe bundling models and native code, in a build with no minification.
  Documented rather than restructured, since cutting ABIs trades away emulator
  support for a build nobody has run yet.

### Still untested

Everything needing hardware. No percept has ever come off a real phone. Motion
and posture thresholds are reasoned from magnitudes, the magnetic baseline time
constant is a guess, and the `ImageProxy` lifetime in `VisionSensor.onFrame`
remains the most suspect code here.

## [0.2.0] — 2026-09-18

Percept schema **1 → 2**. The shape changed; a v0.1 receiver will not parse it.

### Added

- **Radio sense** (`RadioSensor.kt`) — BLE scanning with device counts and named
  devices, WiFi BSSID fingerprinting, registered cell info. MACs and BSSIDs are
  hashed before leaving the device.
- **Place learning** (`PlaceMemory.kt`) — on-device clustering of radio
  fingerprints into persistent, nameable places.
- **Interoception** (`SelfSensor.kt`) — thermal status, thermal headroom, battery
  temperature, current draw, voltage, memory and storage pressure.
- **Full proprioception** (`BodySensor.kt`) — posture from the gravity vector,
  gyroscope RMS, hardware step counter, proximity, magnetic-field anomaly against
  a slow baseline, barometric rate-of-change.
- **Circadian sense** (`TempoSensor.kt`) — offline solar elevation, sunrise and
  sunset by bisection, day length, part of day.
- **Two-way link** (`Link.kt`) — WebSocket transport with eleven inbound commands,
  reconnect backoff, and a `hello` handshake advertising the command vocabulary.
- **Voice** (`Voice.kt`) — TTS out, so the box can answer in the room.
- `tools/receiver.py` rewritten as a stdlib WebSocket server with an interactive
  command console.

### Changed

- **Attention model replaced.** `NoveltyGate` (fixed threshold against the last
  posted percept) is gone; `Habituation.kt` gives every stimulus token a
  persistent trace that saturates with exposure and recovers with time away, plus
  dishabituation and intensity floors.
- Percept restructured into six named sense blocks — `vision`, `hearing`, `radio`,
  `body`, `self`, `tempo` — with a new `attention` block reporting salience,
  novel tokens and any floor reason.
- `Narrator` split into its own file and extended to weave place, interoception
  and time into the prose.
- `Uplink.kt` split: transport moved to `Link.kt`, disk queue to `Spool.kt`.
- Default heartbeat raised 60 s → 120 s, since habituation now handles repetition.
- Motion classification uses gyroscope as well as accelerometer, which separates
  a phone being turned over from a phone in a moving vehicle.

### Fixed

- **Solar elevation double-counted time of day**, returning identical values for
  midnight and noon. The fractional-day term already carries time of day, so the
  separate hour term was wrong. Verified against almanac values.
- **Place matching fragmented badly.** Symmetric Tanimoto similarity split one
  room into many whenever anchors dropped out of a scan, and failed almost
  completely in BLE-only mode because randomised MACs swamped the comparison.
  Replaced with asymmetric persistence-weighted coverage. See
  [docs/VALIDATION.md](docs/VALIDATION.md).
- `ImageProxy.getImage()` opt-in marker was being propagated to callers rather
  than opted into, which would not have compiled.
- `<queries>` was nested inside `<application>` instead of `<manifest>`, silently
  breaking TTS and speech-recogniser resolution on Android 11+.

## [0.1.0] — 2026-09-18

Initial draft. Predates this repository, which was opened at 0.2.0, so there
is no tag to compare against and the heading is deliberately not a link.

### Added

- Vision via CameraX and bundled ML Kit models: image labelling, object detection
  with stable track IDs, Latin-script OCR.
- Hearing via `AudioRecord` with MediaPipe YAMNet classification, degrading to
  loudness and peak when the model asset is absent.
- Offline speech transcription via `SpeechRecognizer`, gated on the classifier
  detecting a voice.
- Ambient and motion sensing: lux, accelerometer RMS, compass heading, barometer,
  battery, network, screen state.
- Deterministic on-device `narration` string.
- Novelty gate comparing label and sound-event sets against the last posted
  percept.
- HTTP POST uplink with a bounded disk spool and backlog flush.
- Foreground service with Android 14 service types and a persistent notification.

[Unreleased]: https://github.com/Tubifix77/synth-senses/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/Tubifix77/synth-senses/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/Tubifix77/synth-senses/releases/tag/v0.2.0
