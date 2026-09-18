# Model assets

Drop model files here. They're gitignored (`*.tflite`, `*.task`) for licence and
size reasons, so each has to be fetched once per clone.

## yamnet.tflite — required for sound classification

Gives you 521 AudioSet sound classes: speech, typing, dog, running water, door,
vehicle, music, and so on. This is what populates `hearing.events`.

Fetch it straight from Google's MediaPipe model store — no account, no browser
step:

```bash
curl -L -o app/src/main/assets/yamnet.tflite \
  https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/latest/yamnet.tflite
```

Check you got the right file:

| | |
|---|---|
| size | 4,126,810 bytes |
| sha256 | `4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf` |

```bash
sha256sum app/src/main/assets/yamnet.tflite
```

The same model is also on Kaggle as `google/yamnet` (the
`tfLite / classification-tflite` variant) and bundled in the MediaPipe audio
classifier sample, if you would rather get it from there.

Rebuild. The control panel stops showing the "no yamnet.tflite in assets" warning
and `status` reports `"sound_model": true`.

### Without it

The app runs fine and degrades quietly:

| | With YAMNet | Without |
|---|---|---|
| `hearing.level_db`, `peak_db` | yes | yes |
| `hearing.events` | 521 classes | `[]` |
| `hearing.transcript` | triggered by `s:Speech` | triggered by peak > −20 dBFS |
| Loud-sound intensity floor | yes | yes |

The fallback speech trigger is cruder — a door slam can set it off — but it works.

## Notes

- `noCompress += listOf("tflite")` is set in `app/build.gradle.kts`. MediaPipe
  memory-maps the file, so a compressed asset fails to load.
- Check the licence on whichever page you download from. See
  [`docs/THIRD_PARTY.md`](../../../../docs/THIRD_PARTY.md).
