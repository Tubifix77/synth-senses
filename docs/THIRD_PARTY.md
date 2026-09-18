# Third-party components

This project is MIT licensed, but it depends on and loads components with their
own terms. Nothing here is legal advice — **verify each licence yourself before
distributing a build**, particularly the model weights.

## Gradle dependencies

| Component | Purpose | Licence (verify) |
|---|---|---|
| AndroidX core, appcompat, lifecycle, activity | platform | Apache-2.0 |
| Jetpack Compose + Material 3 | settings UI | Apache-2.0 |
| CameraX (`androidx.camera:*`) | frame capture | Apache-2.0 |
| ML Kit image labeling | scene labels | Google ML Kit Terms |
| ML Kit object detection | tracked objects | Google ML Kit Terms |
| ML Kit text recognition | OCR | Google ML Kit Terms |
| MediaPipe `tasks-audio` | sound classification runtime | Apache-2.0 |
| OkHttp | HTTP + WebSocket | Apache-2.0 |
| kotlinx-coroutines | concurrency | Apache-2.0 |

ML Kit is distributed under the [Google APIs Terms of
Service](https://developers.google.com/terms) and the [ML Kit
terms](https://developers.google.com/ml-kit/terms), which are not an OSI licence.
The bundled models ship inside your APK; read the terms before shipping a
commercial build.

## Model weights

### YAMNet (`app/src/main/assets/yamnet.tflite`)

**Not included in this repository.** You download it yourself — see
[`app/src/main/assets/README.md`](../app/src/main/assets/README.md).

YAMNet is published by Google, trained on the AudioSet ontology. The model is
generally offered under Apache-2.0, but AudioSet's class ontology and the dataset
it was trained on carry their own attribution terms. Check the licence shown on
the page you download it from — it has moved between hosts and the terms differ
per host.

It is deliberately gitignored (`*.tflite`), for both licence and repository-size
reasons.

### On-device speech recognition

Provided by whatever recogniser the device ships — usually Google's. Not bundled,
not redistributable, and `EXTRA_PREFER_OFFLINE` is a request rather than a
guarantee. On some builds the recogniser will still go to the network. If that
matters to you, leave the speech sense off.

### Text-to-speech

Same: the device's own engine, resolved through the `<queries>` declaration in
the manifest.

## Tools

`tools/receiver.py` uses only the Python standard library. No `pip install`, no
transitive dependencies, nothing to audit. The WebSocket server is implemented
directly against RFC 6455 for that reason.

## What leaves the device

For completeness, since it's a licensing-adjacent question about data rather than
code:

- Camera frames and audio buffers: **never** written to disk, **never** transmitted.
- Derived labels, sound classes, transcripts, numbers: transmitted to your endpoint.
- MAC addresses and BSSIDs: hashed (FNV-1a, 10 hex chars) before transmission.
- Raw radio addresses, raw frames, raw audio: never transmitted.
