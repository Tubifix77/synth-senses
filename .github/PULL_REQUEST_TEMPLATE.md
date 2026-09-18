## What this changes

<!-- One or two sentences. -->

## Testing

- [ ] It compiles
- [ ] Ran on a physical device — which one: 
- [ ] `python3 tools/receiver.py` still works against it
- [ ] Untested (say so plainly — that's allowed)

<!-- If you changed any of the maths in Habituation, PlaceMemory or TempoSensor,
     say how you checked it. docs/VALIDATION.md is the place to record numbers. -->

## Wire format

- [ ] Unchanged
- [ ] Added a nullable field (no version bump needed)
- [ ] Breaking change — bumped `SCHEMA_VERSION`, updated `docs/PROTOCOL.md`

## Privacy

- [ ] Nothing new leaves the device
- [ ] New data leaves the device — described below, and the sense can be switched off

<!-- Defaults stay conservative. Anything needing a special-grant permission
     ships off. -->

## Docs

- [ ] README / PROTOCOL / VALIDATION / CHANGELOG updated as needed
- [ ] No doc change needed
