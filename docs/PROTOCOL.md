# Protocol

Everything on the wire, in one place. Current percept schema: **3** — v3 made `radio.ble_count` and `radio.ble_named` nullable, meaning "could not look".

- [Transport](#transport)
- [Discovery](#discovery)
- [Frames from the phone](#frames-from-the-phone)
- [The percept](#the-percept)
- [Commands to the phone](#commands-to-the-phone)
- [Replies](#replies)
- [Token prefixes](#token-prefixes)
- [Delivery guarantees](#delivery-guarantees)

## Transport

The endpoint URL scheme picks the mode:

| Scheme | Mode |
|---|---|
| `ws://`, `wss://` | full duplex — percepts out, commands in |
| `http://`, `https://` | POST only, no commands |

If a bearer token is set it goes in the `Authorization: Bearer <token>` header —
on the WebSocket upgrade request, or on each POST.

Keepalive pings are sent every 20 s. Reconnects use exponential backoff capped at
60 s.

### HTTP mode

```
POST <endpoint>
Content-Type: application/json
Authorization: Bearer <token>      # if configured

Body: one percept object, or a JSON array of them (backlog flush)
```

Return any 2xx. Anything else, or a timeout, and the percept goes to the disk
spool and is retried with the next successful POST.

## Discovery

Optional, and separate from everything else on this page:
no percept, command or reply ever travels on it. It exists so that nobody has
to type `ws://192.168.0.7:8077/link` on a phone keyboard.

The receiver broadcasts a UDP datagram every **2 s** on port **8078**, to
`255.255.255.255` and to each of its own addresses' `/24` directed broadcast.
`tools/receiver.py` does this by default; `--no-beacon` turns it off and
`--beacon-port` moves it.

```json
{"synth_senses": 1, "port": 8077, "ws": "ws://192.168.0.7:8077/link",
 "host": "boas-laptop"}
```

| Field | Meaning |
|---|---|
| `synth_senses` | Beacon format version. Must be `1`, or the packet is ignored. |
| `port` | The TCP port the receiver is serving on. |
| `ws` | The address to dial. Absent if the sender could not work out its own LAN address. |
| `host` | Hostname, for a person reading a packet capture. Never used to connect. |

Anything on the LAN can send to that port, so the phone treats the contents as
a suggestion: `ws` goes through the same URL rules as hand-typed input, and a
packet that fails them falls back to the address the datagram actually came
from. A beacon never changes a running link — it only fills the Endpoint field,
and only when the user presses **Find receiver**. Nothing listens otherwise.

## Frames from the phone

Every WebSocket frame is a JSON object with a `type` field.

### `hello`

Sent immediately on connect, so your box knows what it's talking to.

```json
{
  "type": "hello",
  "schema": 3,
  "commands": ["sample","look","read","listen","speak","attend",
               "set","places","name_place","forget","status"]
}
```

### `percept`

The main event. A full percept object with `"type": "percept"` added.

### `backlog`

Sent on reconnect if anything was spooled while the link was down. Percepts are
in original order and carry their original timestamps, so they may be
substantially older than the frame.

```json
{ "type": "backlog", "percepts": [ {...}, {...} ] }
```

## The percept

```json
{
  "schema": 3,
  "device_id": "a3f9c1e07b2d",
  "seq": 412,
  "ts": "2026-09-18T14:03:11.220Z",
  "trigger": "salient",

  "attention": {
    "salience": 0.73,
    "novel": ["o:Person", "s:Speech"],
    "known_tokens": 314,
    "dishabituated": false,
    "floor_reason": null
  },

  "vision": {
    "lens": "back",
    "brightness": 0.31,
    "labels": [{"name": "Room", "conf": 0.88}, {"name": "Furniture", "conf": 0.71}],
    "objects": [{"name": "Person", "box": [0.21, 0.08, 0.52, 0.94], "track": 3}],
    "text": "FIRE EXIT"
  },

  "hearing": {
    "level_db": -38.2,
    "peak_db": -14.9,
    "events": [{"name": "Speech", "conf": 0.79}, {"name": "Typing", "conf": 0.41}],
    "classifier": "ok",
    "transcript": "are you still recording"
  },

  "radio": {
    "ble_count": 7,
    "ble_named": [{"id": "9f2a1c0b44", "name": "Kitchen TV", "rssi": -58}],
    "ble_strongest_rssi": -58,
    "wifi_count": 11,
    "wifi_connected": "homenet",
    "wifi_strongest_rssi": -44,
    "cell": {"kind": "lte", "id": "27180417", "dbm": -91},
    "place_id": "p3",
    "place_name": "kitchen",
    "place_similarity": 0.78,
    "place_is_new": false
  },

  "body": {
    "motion": "still",
    "posture": "face_up",
    "accel_rms": 0.03,
    "gyro_rms": 0.01,
    "heading_deg": 187,
    "steps": 8241,
    "steps_delta": 0,
    "lux": 112.0,
    "covered": false,
    "magnetic_ut": 48.2,
    "magnetic_anomaly": 3.1,
    "pressure_hpa": 1012.6,
    "pressure_delta_per_min": 0.02
  },

  "self": {
    "battery": 0.63,
    "charging": false,
    "battery_temp_c": 31.4,
    "current_ma": -412.0,
    "voltage_v": 3.87,
    "thermal": "NONE",
    "thermal_headroom": 0.42,
    "mem_free_pct": 0.38,
    "mem_low": false,
    "storage_free_pct": 0.61,
    "screen_on": false,
    "net": "wifi",
    "uptime_s": 48213
  },

  "tempo": {
    "local_time": "14:03",
    "tz_offset_min": 120,
    "day_of_week": "Friday",
    "part_of_day": "afternoon",
    "solar_elevation_deg": 31.2,
    "is_daylight": true,
    "minutes_to_sunset": 428,
    "minutes_since_sunrise": 512,
    "day_length_min": 940
  },

  "gps": null,
  "narration": "I am in the kitchen. ..."
}
```

### Field notes

Any sense block is `null` when that sense is switched off. Individual fields are
`null` when the hardware isn't present — plenty of phones have no barometer, and
step counters need `ACTIVITY_RECOGNITION`. Write your receiver defensively.

| Field | Notes |
|---|---|
| `device_id` | stable per install, 12 hex chars. Lets one box serve several nodes. |
| `seq` | increments per **posted** percept, not per sample. Gaps mean nothing was worth saying. |
| `trigger` | `first` \| `salient` \| `heartbeat` \| `requested` |
| `attention.salience` | 0–1. What got through the habituation model. |
| `attention.novel` | tokens never seen, or fully recovered. Usually the reason it spoke. |
| `attention.floor_reason` | set when an intensity floor overrode habituation |
| `vision.labels` | what the image labeller recognised, strongest first. **Empty when the frame is too dark to carry content** — mean brightness below 0.10 — because there the labeller answers "Sky, Monochrome, Space" for any black image. `vision.brightness` is still published and says why. |
| `vision.box` | normalised `[left, top, right, bottom]` in 0–1 of the frame |
| `vision.track` | stable across frames while the object stays in view. `null` if untracked. |
| `hearing.*_db` | dBFS, so always negative. `-90` is the floor. |
| `hearing.events` | YAMNet class names. Empty array if `yamnet.tflite` is absent. |
| `radio.*.id` | FNV-1a hash of the MAC or BSSID, 10 hex chars. Never a raw address. |
| `radio.place_similarity` | coverage 0–1, see [VALIDATION.md](VALIDATION.md) |
| `body.motion` | `still` \| `handled` \| `walking` \| `vehicle` |
| `body.posture` | `face_up` \| `face_down` \| `upright` \| `tilted` \| `pocket` \| `unknown` |
| `body.magnetic_anomaly` | µT deviation from a slow baseline. Above ~15 means something metal or electrical is close. |
| `body.pressure_delta_per_min` | hPa/min. Catches lifts, stairs, doors in sealed rooms. |
| `gps` | null while `location` is off. When on, the phone asks for its own fixes about once a minute, from the network provider (Wi-Fi and cell, works indoors) and GPS. |
| `gps.lat`, `gps.lon` | degrees, WGS 84. Read them with `acc_m`: indoors a fix is typically tens of metres wide, however many decimals it prints. |
| `gps.acc_m` | 68% confidence radius in metres, as Android reports it. |
| `gps.age_s` | seconds since the fix was taken. A still phone may keep one fix for a long time, and that is fine; a large age on a phone that has moved means a stale position. Null if unknown. |
| `body.heading_deg` | compass bearing from the rotation vector. **Null when the magnetic field is outside 20–75 µT**, Earth's range anywhere on the surface: a field that far off means something local dominates, and the bearing points at it. `body.magnetic_ut` is still published and says why. |
| `radio.ble_count` | devices heard in the scan window. **Null when the scan did not happen or cannot be trusted** — BLE off, permission denied, the platform refusing a scan started too soon after the last five, or, above Android 12, the screen off with nothing heard. Null is not zero: zero means it looked and the room was empty. |
| `radio.ble_named` | named devices from the same scan, strongest first, at most 8. Null exactly when `ble_count` is. |
| `self.charging` | connected to external power, whether or not current is flowing in at this moment. A phone holding its charge at 80% on a charger still reads `true`; the sign of `current_ma` says which way the current is going. |
| `self.current_ma` | negative while discharging. **Null until the phone's unit is established** — Android documents the underlying property as microamps and some vendors report milliamps, so the reading is withheld rather than published 1000x wrong. `status` reports `current_unit` once it is known. |
| `self.thermal` | `NONE` \| `LIGHT` \| `MODERATE` \| `SEVERE` \| `CRITICAL` \| `EMERGENCY` \| `SHUTDOWN` \| `UNKNOWN` |
| `hearing.classifier` | `ok` \| `absent` \| `failed` \| `null` — why `events` is empty, when it is. `ok` means it ran and heard nothing above threshold, so the silence is real; `absent` means no model is loaded; `failed` means it threw. `null` only on percepts from before this field existed. |
| `self.thermal_headroom` | 0 fine → 1 about to throttle. `null` below Android 11, or if polled under 10 s apart. |
| `tempo.solar_elevation_deg` | `null` without a location fix |
| `narration` | deterministic prose built on-device. Safe to feed straight to an LLM. |

## Commands to the phone

Any JSON object with a `cmd` field. Unknown commands get a negative ack rather
than being ignored. All commands ack except the ones that return data.

**How one gets there from a program.** The phone receives commands over the
websocket; the reference receiver puts them on that socket for you. Write one
JSON object per line to `tools/receiver.py`'s **stdin** and it is forwarded to
every connected node — the mirror image of percepts leaving on stdout, so the
whole sensing side is a bidirectional filter with no extra port or protocol.
The receiver reads stdin as commands whenever stdin is not a terminal; when it
is, a person gets the console instead.

The receiver forwards only the eleven commands below. Anything else is refused
there and never reaches the phone, as is a malformed line or one sent while no
node is connected — each answers `{"type": "error", ...}` so the sender knows,
rather than waiting on an ack that is never coming.

Replies travel back the same way, on stdout alongside the percepts. **A reply
always carries `type`; a percept never does** — the receiver strips the
transport envelope from every percept so that discriminator holds whichever way
it arrived. See [INTEGRATION.md](INTEGRATION.md) for the consumer's side.

### `sample`

```json
{"cmd": "sample"}
```

Take a percept immediately, tagged `"trigger": "requested"`, bypassing the
salience gate. Cuts short the current wait rather than queueing until the next
tick.

### `look`

```json
{"cmd": "look", "lens": "front", "persist": false}
```

Switch eyes for one sample. `lens` is `front` or `back`. Set `persist: true` to
make it the new default.

### `read`

```json
{"cmd": "read"}
```

Force OCR on this sample even if the OCR toggle is off.

### `listen`

```json
{"cmd": "listen"}
```

Force speech transcription now, skipping both the "did it sound like a voice"
check and the 20 s minimum gap.

### `speak`

```json
{"cmd": "speak", "text": "I can see you"}
```

Speak through the phone's TTS. Capped at 600 characters. Initialises the voice
engine on demand even if the toggle was off.

> Anything spoken will be heard and transcribed on the next hearing sample. That
> feedback loop is left open deliberately.

### `attend`

```json
{"cmd": "attend", "modality": "s", "gain": 2.0}
```

Multiply the salience of every token with that prefix. Gain is clamped to 0–5.
`1.0` removes the gain. Omit `modality` entirely to clear all gains.

`{"cmd":"attend","modality":"v","gain":0.2}` is roughly "stop caring about the
walls".

### `set`

```json
{"cmd": "set", "interval_ms": 15000, "threshold": 0.2, "ble": true}
```

Change any number of settings in one call. Senses can be switched on and off at
runtime; the service constructs or releases the relevant sensor.

| Key | Range |
|---|---|
| `interval_ms` | 1 000 – 600 000 |
| `heartbeat_ms` | 5 000 – 3 600 000 |
| `threshold` | 0.0 – 1.0 |
| `vision`, `hearing`, `speech`, `ocr`, `ble`, `wifi`, `cell`, `location`, `voice` | boolean |
| `lens` | `"front"` \| `"back"` |

**The reply names what was applied**, key by key, and lists anything it did not
recognise — `ok` is false when any key was unrecognised:

```json
{"type": "ack", "cmd": "set", "ok": true, "detail": "applied speech=false, wifi=true"}
{"type": "ack", "cmd": "set", "ok": false, "detail": "nothing applied; not a setting: nonsense"}
```

Two things that used to be silently wrong here, because both are the kind of
thing a consumer would never discover: `location` and `voice` were **not
handled at all**, so switching the GPS on over the link was accepted and
discarded; and the reply was an unconditional `"settings applied"`, so a call
containing nothing but a typo reported success.

A `set` that changes anything also makes the on-device control panel re-read
its switches. The panel keeps a snapshot, so without that a phone whose
microphone had been switched on from off-device went on showing it off.

### `places`

```json
{"cmd": "places"}
```

Returns a `places` reply.

### `name_place`

```json
{"cmd": "name_place", "id": "p3", "name": "kitchen"}
```

Names a learned place. The name then appears in `radio.place_name` on every
subsequent percept from there, and in the narration.

### `forget`

```json
{"cmd": "forget", "what": "habituation"}
```

`what` is `habituation` (default), `places`, or `all`. Forgetting habituation
makes the entire world novel again, which is a useful way to get a full
re-description of the current scene.

### `status`

```json
{"cmd": "status"}
```

Returns a `status` reply including `most_habituated` — the tokens it has stopped
noticing, with exposure counts. Worth reading when output has gone quiet and you
want to know why.

It also reports `device` and `sensors`: which phone this is, and which of the
sensors the body sense uses are physically present, by the hardware's own
names. The app degrades silently on missing hardware, so without this the only
evidence is a logcat line and a USB cable. **Type `status` first on a new
phone** — it is the difference between knowing there is no barometer and
assuming it from a spec sheet.

## Replies

### `ack`

```json
{"type": "ack", "cmd": "attend", "ok": true, "detail": "gain s=2.0"}
```

### `places`

```json
{
  "type": "places",
  "places": [
    {"id": "p1", "name": "desk", "visits": 412, "anchors": 34,
     "stable_anchors": 21,
     "first_seen": "2026-09-01T08:00:00.000Z",
     "last_seen": "2026-09-18T14:00:00.000Z"}
  ]
}
```

`stable_anchors` is how many anchors pass the persistence filter and actually
contribute to recognition. A place with very few is one that will recognise
unreliably.

### `status`

```json
{
  "type": "status",
  "device_id": "a3f9c1e07b2d", "schema": 3, "seq": 412,
  "device": {"model": "23078PND5G", "manufacturer": "Xiaomi",
             "android": "14", "sdk_int": 34},
  "sensors": {
    "light":       {"present": true,  "name": "TMD2755 Light", "feeds": "body.lux, and the dark half of pocket"},
    "pressure":    {"present": false, "feeds": "body.pressure_hpa and its drift"},
    "step_counter":{"present": true,  "name": "Step Counter", "feeds": "body.steps and steps_delta"}
  },
  "interval_ms": 5000, "heartbeat_ms": 120000, "threshold": 0.35,
  "senses": {"vision": true, "lens": "back", "ocr": true, "hearing": true,
             "sound_model": true, "speech": false, "ble": true, "wifi": false,
             "cell": false, "location": false, "voice": false},
  "habituation": {
    "tokens": 314, "quiet": 201, "mean": 0.64,
    "gains": {"s": 2.0},
    "most_habituated": [{"token": "v:Room", "exposures": 1904}]
  },
  "places": 2,
  "spooled": 0
}
```

## Token prefixes

Habituation operates on these strings; `attend` targets them by prefix.

| Prefix | Meaning | Example |
|---|---|---|
| `v:` | vision label | `v:Room` |
| `o:` | detected object class | `o:Person` |
| `oc:` | bucketed object count | `oc:2-3` |
| `tx:` | hash of OCR text | `tx:-19022` |
| `s:` | sound class | `s:Speech` |
| `db:` | loudness bucket, tens of dB | `db:-3` |
| `sp:` | hash of a transcript | `sp:88214` |
| `p:` | learned place | `p:p3` |
| `bc:` | bucketed BLE device count | `bc:4-8` |
| `bn:` | named BLE device | `bn:Kitchen TV` |
| `ap:` | connected WiFi SSID | `ap:homenet` |
| `m:` | motion state | `m:still` |
| `po:` | posture | `po:face_up` |
| `lx:` | light bucket | `lx:indoor` |
| `mag:` | magnetic anomaly present | `mag:anomaly` |
| `th:` | thermal status | `th:NONE` |
| `chg:` | charging | `chg:true` |
| `bat:` | battery low | `bat:low` |
| `tod:` | part of day | `tod:afternoon` |

Continuous quantities are bucketed deliberately. A room at 310 lux and the same
room at 340 lux must produce the same token, or nothing ever habituates to
anything. Tune the vocabulary in `Percept.tokens()`.

## Delivery guarantees

**At-least-once, loosely.** The spool is bounded at 2 000 percepts with oldest
dropped first, so a link down for a very long time loses the oldest history
rather than growing without limit.

In HTTP mode a percept can be delivered twice if the response is lost after the
server committed it. Deduplicate on `(device_id, seq)` if that matters — `seq`
increments monotonically per posted percept and never repeats within a run.

`seq` resets to 0 when the service restarts, so `(device_id, seq)` is not unique
across restarts. Use `ts` to disambiguate.
