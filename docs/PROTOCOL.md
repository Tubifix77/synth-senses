# Protocol

Everything on the wire, in one place. Current percept schema: **2**.

- [Transport](#transport)
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

## Frames from the phone

Every WebSocket frame is a JSON object with a `type` field.

### `hello`

Sent immediately on connect, so your box knows what it's talking to.

```json
{
  "type": "hello",
  "schema": 2,
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
  "schema": 2,
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
| `vision.box` | normalised `[left, top, right, bottom]` in 0–1 of the frame |
| `vision.track` | stable across frames while the object stays in view. `null` if untracked. |
| `hearing.*_db` | dBFS, so always negative. `-90` is the floor. |
| `hearing.events` | YAMNet class names. Empty array if `yamnet.tflite` is absent. |
| `radio.*.id` | FNV-1a hash of the MAC or BSSID, 10 hex chars. Never a raw address. |
| `radio.place_similarity` | coverage 0–1, see [VALIDATION.md](VALIDATION.md) |
| `body.motion` | `still` \| `handled` \| `walking` \| `vehicle` \| `unknown` |
| `body.posture` | `face_up` \| `face_down` \| `upright` \| `tilted` \| `pocket` \| `unknown` |
| `body.magnetic_anomaly` | µT deviation from a slow baseline. Above ~15 means something metal or electrical is close. |
| `body.pressure_delta_per_min` | hPa/min. Catches lifts, stairs, doors in sealed rooms. |
| `self.current_ma` | negative while discharging |
| `self.thermal` | `NONE` \| `LIGHT` \| `MODERATE` \| `SEVERE` \| `CRITICAL` \| `EMERGENCY` \| `SHUTDOWN` \| `UNKNOWN` |
| `self.thermal_headroom` | 0 fine → 1 about to throttle. `null` below Android 11, or if polled under 10 s apart. |
| `tempo.solar_elevation_deg` | `null` without a location fix |
| `narration` | deterministic prose built on-device. Safe to feed straight to an LLM. |

## Commands to the phone

Any JSON object with a `cmd` field. Unknown commands get a negative ack rather
than being ignored. All commands ack except the ones that return data.

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
| `vision`, `hearing`, `speech`, `ocr`, `ble`, `wifi`, `cell` | boolean |
| `lens` | `"front"` \| `"back"` |

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
  "device_id": "a3f9c1e07b2d", "schema": 2, "seq": 412,
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
