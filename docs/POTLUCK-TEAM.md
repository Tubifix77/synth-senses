# The Potluck projects

A roster, not a process. Five projects share the Potluck platform and most are
asleep at any time, so this is somewhere to look up who does what — nothing
more. Tue runs things one at a time, and the projects hand over to each other
when they finish a piece. That has been enough.

Last reviewed 2026-10-22.

## Who is who

| Project | What it is | Deploys |
|---|---|---|
| `potluck` | the hub and the cell runtime | board firmware |
| `synth-senses` | an Android phone publishing `act/phone/*` | the mobile app |
| `potluck-desk` | the Potluck Virtual Transceiver, the desk UI | PC software |
| `passive-sensor` | presence and position from the boards' own radio links | — apps live in Potluck's image |
| `poor-mans-extender` | a Wi-Fi extender on three ESP32-S3 | — same |

Whoever finishes first deploys, to get tests moving. The three deploy targets
are independent, so there is no reason to ship them together.

## Worth knowing before you change something

The ones that are easy to forget and expensive to rediscover.

- **The phone's value set is at its twelve-value cap.** Adding one means
  removing one, and removal is the breaking direction: the desk's view matches
  only an inbox carrying the whole set, so dropping a value makes the phone's
  panel vanish entirely rather than lose a dial.
- **`potluck-desk` pins `potluck_bridge.py --vocabulary`.** Renaming or
  reordering a label breaks its build, which is the point. Appending is safe.
- **Never run two bridges.** The hub counts a program's write share by pairing
  name, so two copies look like one program at double rate.
- **The extender role moves the whole cell's Wi-Fi channel.** While it is
  active one board joins the house mesh and the cell follows the router, and
  every channel change costs each `passive-sensor` board about a minute
  relearning its links. During a scored presence test that reads as the method
  being wrong rather than as interference, which is why it is worse than it
  sounds.
- **`poor-mans-extender` is finished** — parked 2026-10-16, last commit
  `8e499ab`, and its README says not to assume any board runs it.
- **The boards belong to Potluck's cell.** `passive-sensor` and
  `poor-mans-extender` are components compiled into one firmware image; neither
  flashes anything itself.

## Where things stand

- `passive-sensor` has **PS-1** written and ready — a presence test in the
  house, blind-scored against a sealed route file. It needs a board deploy and
  the ground floor to itself for about 45 minutes.
- `potluck` and `potluck-desk` are on hiatus, but the PS-1 deploy no longer
  depends on them: it is written down in `passive-sensor`'s `PS1-RUNSHEET.md`
  under "Deploying without the Potluck session", with the verification steps
  beside it. Tue can run it himself if `apply ... inventory` shows all three
  boards enrolled.
- `synth-senses` is active, on phone-side work that touches nothing here.
