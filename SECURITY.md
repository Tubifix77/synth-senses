# Security and privacy

## Reporting a vulnerability

Open a [private security advisory](https://github.com/Tubifix77/synth-senses/security/advisories/new)
rather than a public issue.

Please include what you can reproduce, the device and Android version, and
whether the issue exposes data off-device.

## What this software is

An app that continuously watches a room through a camera, listens through a
microphone, transcribes nearby speech, scans for nearby radio devices, and streams
a description of all of it to a network endpoint.

Treat it accordingly. The threat model is not "someone steals the source" — it's
"this thing is pointed at people".

## What leaves the device

| Leaves | Never leaves |
|---|---|
| Image labels, object classes, bounding boxes | camera frames |
| Sound classes, loudness figures | audio buffers |
| Speech transcripts | raw audio of speech |
| Hashed BLE MACs and WiFi BSSIDs, signal strengths | raw radio addresses |
| Sensor numbers, battery and thermal state | — |
| Coarse GPS, only if explicitly enabled | precise location, unless enabled |
| `narration` prose assembled from the above | — |

Radio identifiers are hashed with FNV-1a and truncated to 10 hex characters. That
gives your box stable identity without addresses, but note it is a **fast
non-cryptographic hash over a small space** — an attacker who already holds a
candidate MAC list can confirm membership by hashing it. It defends against
casual address harvesting, not against a determined adversary with a target list.

## Known exposures

**The spool.** Undelivered percepts, transcripts included, sit in plaintext at
`/data/data/net.synthsenses/files/spool.jsonl` until they're delivered. It's
inside the app sandbox, so it needs root or ADB backup to read, but it is not
encrypted. It holds up to 2 000 percepts.

**Learned places and habituation traces.** Also plaintext, in the same directory.
`places.json` is a radio map of everywhere the phone has been. It contains hashed
identifiers rather than addresses or coordinates, but it is still a movement
history.

**Cleartext transport is permitted.** `network_security_config.xml` allows
plaintext HTTP so you can POST to a LAN IP. That means transcripts of what people
said can cross your network in the clear. Set
`cleartextTrafficPermitted="false"` and use `wss://` for anything leaving the LAN.

**No authentication on the receiver by default.** `tools/receiver.py` accepts any
connection unless you pass `--token`. It's a development tool. Don't expose it to
the internet.

**The bearer token is stored in SharedPreferences**, unencrypted. Use a token
scoped to this purpose, not a credential that matters elsewhere.

**The voice feedback loop.** With both voice and speech transcription enabled,
anything the box speaks is heard and transcribed on the next sample. Left open
deliberately, but it means your AI's own output re-enters its input.

## If you run this

- Tell people in the room. In many jurisdictions recording or transcribing
  speech without consent is illegal, and "it only sends labels" is not a defence
  when one of the labels is a transcript.
- Prefer `wss://` and a token.
- Leave `speech` and `location` off unless you need them. They're off by default.
- Point the back camera at a wall while testing, not at people.
- Remember that the persistent notification is the only outward sign the phone is
  sensing. Don't remove it.

## Not in scope

- Rooting, ADB access, or an attacker who already controls the device.
- Whatever your box does with percepts once delivered.
- The privacy practices of the device's speech recogniser or TTS engine.
