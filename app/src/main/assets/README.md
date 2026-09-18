# Model assets

Drop model files here. They're gitignored (`*.tflite`, `*.task`) for licence and
size reasons, so each has to be fetched once per clone.

## yamnet.tflite — required for sound classification

Gives you 521 AudioSet sound classes: speech, typing, dog, running water, door,
vehicle, music, and so on. This is what populates `hearing.events`.

Get it from either:

- **Kaggle Models** — `google/yamnet`, the `tfLite / classification-tflite`
  variant
- the **MediaPipe audio classifier** sample app, which bundles a copy

Then:

```bash
cp ~/Downloads/yamnet.tflite app/src/main/assets/yamnet.tflite
```

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
