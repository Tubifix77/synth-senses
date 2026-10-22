# The Potluck working order

Five projects share the Potluck platform. Four of them are usually asleep, and
the sessions that know them come and go, so what each owns and what each may
break has to live in a file rather than in a conversation.

**Written and maintained by the Synth Senses session**, because that is the one
Tue asked to write it. That matters for how to read it: every claim is marked
**confirmed** where the owning project said it itself, or **assumed** where it
is Synth Senses' inference and nobody has checked it. Do not act on an
*assumed* line without asking its owner first.

Last reviewed 2026-10-22.

---

## Who owns what

### `synth-senses` — the phone (this repo)

An Android phone publishing twelve values into a Potluck cell as
`act/phone/*`, over `tools/potluck_bridge.py`.

- **Owns:** the published value set and its labels, exposed as
  `python tools/potluck_bridge.py --vocabulary`.
- **Consumed by:** `potluck-desk`, which pins that vocabulary and asserts its
  labels against it. A rename here is a red build there, on purpose.
- **State:** active. Running against the real hub, certificate pinned, all
  twelve values writing.
- **Working on next:** an offline speech recogniser, a `current_ma` that is
  1000× out on Huawei, and the motion thresholds above `still`. None of it
  touches Potluck.

### `passive-sensor` — RF presence and position **(confirmed)**

Presence and position derived from the ESP32 boards' own radio links to each
other.

- **Owns**, per its `DECISIONS.md` D20:
  - module names `ext:passive_sensor/rf_fusion` (`per_room`), `rf_link`,
    `rf_indicator`
  - `rf_fusion`'s `act/{actor}/out`, `confidence`, `motion`, `state`, and the
    `?` outputs `x, y, a, b, theta, conf`, with their encodings
  - `rf_link`'s `{node}/rf/channel` and
    `{node}/rf/link/{peer}/{seq,count,mean,var}`
  - the descriptor's **`panel`** block — `rf_fusion`'s room card on Potluck's
    dashboard — and its **`view`** block, the Potluck Desk's "Room presence"
    window. Both live in `potluck-modules.json`.
- **State: on hold.** **PS-1** is written and ready but blocked on who deploys
  while `potluck` is on hiatus, and the boards' current state is unknown — it
  assumes nothing and starts from a fresh deploy. PS-1 is a presence test in
  the house: a person stands on taped spots, then walks two routes the PC calls
  out, with the route file sealed under a sha256 until the reconstruction is
  committed and scored blind.
- **Needs nothing from `synth-senses`**, and has no plans to ask for a phone
  value.
- **May later ask Potluck** to measure the house mesh's Wi-Fi beacons as extra
  radio lines. That would be a Potluck change and it will announce first.

### `potluck` — the hub and the cell runtime

- **Owns:** the hub API, the write rate limit (2 writes/second, bursts of 4,
  counted **per pairing name** — two copies of one program look like one
  program at double rate), unit names via `Unit[unit.upper()]` in `deploy.py`,
  package applies, and the **twelve-value inbox cap**.
- **State:** on hiatus. Last known good: package 106 applied, `lat`/`lon`
  landing. Fixed `6c73d00` so a mid-apply `NOT_FOUND` answers 404 rather than
  403 — not yet installed on Tue's PC, so the running hub still sends the 403.
- **Everything below the phone depends on it**, and **every board deploy so far
  has gone through this session**, which is why its absence is currently a
  blocker (see below).

### `potluck-desk` — Potluck Virtual Transceiver

- **Owns:** the desk UI and its relay to Potluck.
- **Consumes:** `synth-senses --vocabulary`, pinned.
- **State:** on hiatus. Shipped the redesign Tue asked for — "Potluck devices"
  rather than Boards, "Cell resources" rather than Board hardware, locality as
  metadata.
- Found the drain-starvation bug in the phone's bridge by reading its source
  and reporting what the hub was seeing. That exchange is the model this order
  is built on.

### `poor-mans-extender` — Wi-Fi extender with three ESP32-S3

- **State: finished (confirmed).** Its README reads "Status (2026-10-16):
  done, by the owner's call, and parked", and "Do not assume any board runs it
  now". Last commit `8e499ab`. **No session will touch the boards on its
  behalf.**
- **Not a separate firmware owner.** Like `passive-sensor`, it is a component
  compiled into Potluck's firmware image and deployed the same way — `potluck
  apply`, via the cluster file's `firmware.extender` entry. On the bench, board
  B ran "Potluck + pme_hotspot + rf_link in one image". Neither project flashes
  anything itself.
- **But its *role* is the live risk to PS-1**, which is not what this file
  first guessed. See below.

---

## Shared things, and who can break them

| Resource | Owner | How it breaks |
|---|---|---|
| The twelve-value inbox | `potluck` | **At its cap.** Adding a value means removing one, and removal is the breaking direction: the desk's view matches only an inbox carrying the whole set, so dropping one makes the phone's panel vanish entirely rather than lose a dial. |
| `--vocabulary` labels | `synth-senses` | Renaming, reordering or inserting breaks `potluck-desk`'s build. Appending does not. |
| `rf_fusion` / `rf_link` paths | `passive-sensor` | Renaming breaks the desk's view of presence. |
| Hub write rate | `potluck` | Two copies of one program look like one over its share. **Never run two bridges.** |
| The three ESP32-S3 boards | **Potluck's cell (Tue)** | The boards belong to the cell; `passive-sensor` and `poor-mans-extender` are apps inside one firmware image, not joint firmware owners. Neither flashes its own. |
| The extender **role** | `potluck` cluster file | While active, one board joins the house mesh and **the whole cell follows the router's channel** (L3.8, L3.28). Every channel change makes each `passive-sensor` board relearn its links (D14) — about a minute blind. |
| GPU / Ollama | *unassigned* | `dark-factory` (not a Potluck project) runs only when Tue says the GPU is free. Synth Senses may want it later to evaluate a speech model, and will ask first. |

---

## What the hardware question turned out to be

This file was written asking whether `passive-sensor` and `poor-mans-extender`
flash the same three boards and would therefore contend. **They do share the
boards and they do not contend over firmware** — both are components compiled
into one Potluck image, deployed by `potluck apply`. A reflash by a rival
project was the wrong worry.

The real risk is subtler and was found by `passive-sensor`, not here. When the
**extender role** is active, one board joins the house mesh, and the whole cell
then follows the router's channel. Each channel change costs every
`passive-sensor` board about a minute of relearning its links — which, during a
blind-scored walk, is indistinguishable from the method simply being wrong. A
reflash would at least have been obvious; this would have quietly inflated the
error and been believed.

PS-1 therefore leaves the extender role idle for the session and records that
choice (`56067a6`). Worth keeping as a general shape: **the dangerous
interaction between two projects was not the one either of them owned, it was a
property of the platform they both sit inside.**

## Open, and only Tue can settle it

1. **Who deploys the boards for PS-1.** Every deploy so far went through the
   `potluck` session, which is on hiatus. `passive-sensor` needs a fresh deploy
   and the ground floor undisturbed for about 45 minutes. It has explicitly left
   this to Tue, and it is now the only thing standing between PS-1 and a run.

---

## The working rules

Proposed by Tue, agreed by `synth-senses` and `passive-sensor`:

1. **Each project works on its own thing** and does not block the others.
2. **Announce before landing a change to a shared contract** — value names,
   units, ordinals, module paths, the inbox set. Not after.
3. **Say when something is iffy, at the point you notice it**, not once it has
   landed. A doubt costs a sentence; a wrong assumption costs someone's evening.
4. **Read the other project's source before reporting a symptom.** The desk
   session diagnosed a starvation bug in the phone's bridge that way, and it was
   worth more than any test on this side.
5. **Correct your own numbers when they turn out to be wrong**, even when the
   other project has already acted on them and the conclusion still holds.

---

## Checkup, to paste into a session that wakes up

Four questions. Answer at whatever length is useful; "nothing planned, nothing
owed" is a complete answer.

1. **What are you planning next?** Enough detail that others can tell whether
   it touches them.
2. **What shared contract do you own** — names, units, encodings, paths — that
   someone else reads?
3. **Does anything you plan depend on another project, or change something
   another project consumes?**
4. **What on your list is iffy?** Uncertain, risky, or likely to surprise
   someone.

Then send the answers to the other Potluck sessions that are running, and ask
whoever maintains this file to fold them in — replacing any **assumed** line
about your project with a **confirmed** one.
