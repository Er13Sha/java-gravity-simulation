# W-only mouse-steered flight — strategy

## 1. Goal / intended feel

Hold **W** to thrust; hold **left mouse** and drag to aim. Together they fly the camera in any direction (including up/down) by steering with the look direction. No strafe / reverse / side keys. Orbit button and click-to-body focus stay as they are.

## 2. Current behavior (from code)

| Input | Behavior |
|--------|----------|
| **W A S D** | Move look-target offset in the **horizontal** view plane (yaw only; pitch ignored) |
| **Q / E** | World up / down (`oy`) |
| **Shift** | Flight speed × `Camera.BOOST` (3) while any move key held |
| **LMB drag** | Rotate yaw/pitch (`Camera.rotate`) |
| **LMB click** (&lt; `CLICK_PX` 5px) | Body → smooth focus; empty → clear focus / close theme panel |
| **Wheel** | Zoom `camR` |
| **Orbit chip** | Toggle stable-orbit overlay when ready — unrelated to flight |

Key files:

- `src/main/java/uif/ui/Camera.java` — `update(...)` WASD/QE offset; `rotate` / `focusOn`
- `src/main/java/uif/ui/App.java` — key dispatcher (`VK_W`…`E`, Shift), `cam.update`, body/empty click wiring
- `src/main/java/uif/ui/ScenePanel.java` — LMB look vs click (`looking`, `CLICK_PX`)
- Copy: `ControlPanel` scene note, `HelpTopics`, `README.md`

**Gap vs goal:** W already moves “forward” by yaw, and LMB already turns the view while W is held — but forward **does not follow pitch**, so aiming up/down does not steer flight vertically. A/S/D still provide strafe/back.

## 3. Proposed control model

1. **W only** — thrust along **full camera forward** (yaw **and** pitch), same distance-scaled speed and Shift boost.
2. **Remove A, S, D** from key set and from `Camera.update` (no strafe / reverse).
3. **LMB hold + drag** — unchanged look; while **W** is held, changing look continuously steers thrust → fly any direction.
4. **W without LMB** — keep thrusting along current look (aim fixed until user looks again).
5. **Q / E** — **recommendation: keep** as world up/down for fine vertical without pitching the view. If the user wants pure “W + mouse only,” remove Q/E in the same pass (open question below).
6. Docs/UI notes: “W — вперёд (по взгляду), Shift — ускорение, Q/E — вверх/вниз” (drop A S D); HelpTopics / README match.

No change to orbit button, RMB aim, wheel zoom, or click-focus pipeline.

## 4. Edge cases

| Case | Plan |
|------|------|
| **Click-to-focus vs look-drag** | Keep `CLICK_PX` gate: small release = click (body/empty); drag = look only. W does not change that. |
| **Shift + W-only** | Boost remains Shift×W (and Q/E if kept). No boost without a move key. |
| **Empty click** | Unchanged: clear body focus (fold offset into COM) + close theme panel. |
| **Orbit button** | Untouched. |
| **Focus + W** | After `focusOn` zeros offset, W again accumulates `ox/oy/oz` relative to focused body / COM — same as today. |
| **Help open** | Flight keys still ignored in help window (existing dispatcher rule). |

## 5. Files to change (implementation turn — not this turn)

- `Camera.java` — forward vector with pitch; drop A/S/D (and Q/E if decided)
- `App.java` — stop mapping A/S/D (and Q/E if decided); comment on `installKeys`
- `ControlPanel.java` — bottom scene control note
- `HelpTopics.java` — flight paragraph + any HUD hint strings
- `README.md` (and `НАСТРОЙКА.md` if it still lists WASD) — one-line control docs

No ScenePanel mouse logic required unless verification finds click/look conflict (unlikely).

## 6. Verification steps

1. Hold **W** only → move along current look; release → stop.
2. Hold **W** + **LMB drag** → path curves with aim, including steep pitch (up/down).
3. **A / S / D** do nothing.
4. **Shift+W** faster; without W, Shift alone does nothing.
5. Short LMB on a body → focus zoom; short LMB on empty → clear/close; drag past 5px → no accidental focus.
6. **Orbit** chip still toggles when ready.
7. Skim Help / control note — no leftover “WASD” / A S D.

## 7. Open questions for user

1. **Q / E:** keep world up/down, or remove so vertical is only via pitch + W?
2. **S as reverse?** Explicitly out of scope (remove) — confirm no brake/reverse key is wanted.
3. Should **W without mouse** stay “thrust along last look,” or require LMB for any flight? (Proposal: W alone is enough.)
