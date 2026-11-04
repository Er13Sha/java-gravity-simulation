# java-gravity-simulation

3D interactive simulation of gravity under **W = Q·N** (Java / Swing). Bodies are uniform spheres; forces, orbits, and the UI live in one desktop window.

This repository is set up so a **clone can run without application source**. Source under `src/` is **gitignored** (kept locally by maintainers). The tracked runnable JAR is `build/input/universal-interaction-formula.jar`.

## Run (no source needed)

Needs a JRE/JDK that can launch the JAR (the build scripts expect **JDK 22+** when you rebuild).

```bat
run.bat
```

`run.bat` starts `build/input/universal-interaction-formula.jar`. If the JAR is missing, it calls `build.bat` first (that path needs local `src/`).

## Build (local source)

If you have `src/main/java` and `src/test/java` locally:

```bat
build.bat
```

`build.bat` runs `build.ps1`, which:

1. Finds JDK 22+ (`JAVA_HOME`, then `javac` on `PATH`, then `C:\Program Files\Java\jdk-*`)
2. Compiles sources to `build/classes`
3. Runs `uif.FormulaTest`, `uif.ui.GlSceneTest`, `uif.ui.CloudTest`
4. Writes `build/input/universal-interaction-formula.jar` and `build/jdk.txt` (used by `run.bat`)

No Maven/Gradle. CUDA / OpenGL are optional at runtime (CPU and Java2D paths exist).

## What’s in the window

Theme strip (left → right): **Сценарий · Частицы · Тяготение · Расчёт · Вид**.  
Always on the right: pause, reload, reset, time speed.

### Сценарий

Radio list + details. Order:

| Preset | Notes |
|---|---|
| Земля–Луна | Loads **immediately** on select. Earth `N ≈ 1e13`, radius = 1 R⊕; Moon mass = Earth × editable share (default `1/81.3`), radius ≈ 0.273 R⊕; astronomical length scale |
| Солнечная система | Loads **immediately**. Sun + planets + major moons + main belt (~2.2–3.3 AU) + Kuiper (~30–48 AU) on full `AU_RE ≈ 23455` R⊕ orbits. **Cosmic radii** independent of density (Sun ≈ 109 R⊕ ≫ planets ≫ moons ≫ belt `~5e-5…5e-3` R⊕). Belt masses `~3.3…~3.3e3` m_e. **Visual classes** (STAR / PLANET+MOON / ASTEROID palette + mass-log sub-pixel halo; physics rad unchanged). Each append gets a **random pack plane**; bodies get modest per-orbit **inclination** (planets ≲7°, moons ≲12°, belts ≲25°) so a single SS is not coplanar |
| рандомная солнечная система | Loads **immediately** (append). Random star (~0.25…4 M☉) + 4…12 planets with varied masses/radii/semi-axes, optional moons, 0…2 belts; same cosmic `AU_RE` scale, STAR ≫ PLANET ≫ MOON ≫ ASTEROID, random pack plane + per-body inclinations (as SOLAR). Also in RMB **создать здесь → сценарий** |
| большая звезда | Loads **immediately** (append). Single `STAR`: ~100 M☉, radius ≈ 80× Sun (≫ Sun in N and rad). Tab + RMB |
| Песочница | Loads **immediately**. Empty scene |
| **мелкие фракции** | Last in the list; does **not** replace the scene. Count chooser **100 / 500 / 2000 / 5000** (default 2000); **применить** **appends** asteroid-like masses (`~3.3e2…~3.3e7` m_e) on top of the current scenario and turns **непроницаемые** on (you can turn it off afterward) |

**Непроницаемые** lives on this tab (soft contact shell). It is not under **Вид**.

### Other tabs (short)

- **Частицы** — add/remove p, n, e, atom, dipole, custom mass; `+ рандом`; spawn mode (orbit / rest / throw)
- **Тяготение** — **комплексное (КУО) only** (no «прямое» mode); β scales + G default **1** (honest / 1 chips + log₁₀ slider)
- **Расчёт** — time speed ×⅛…×1024 (default ×1); compute path default **GPU float** (авто / CPU / GPU double / GPU float / **GPU float Barnes–Hut**); shaders
- **Вид** — field / trails / orbits / labels / chamber wall; help

### Camera & keys

- Mouse drag — look; click body — smooth focus; wheel — zoom; right-click — spawn here / help
- **W** — thrust along full look direction (yaw **and** pitch); **Shift** — boost; **Q** / **E** — world up / down (no A/S/D)
- Space — pause; R — reset current preset; G — field; T — trails; Esc — close theme panel; F1 — help

Design note for W-only flight: [`docs/w-only-flight-strategy.md`](docs/w-only-flight-strategy.md).

## Repository layout (tracked)

| Path | Role |
|---|---|
| `build/input/universal-interaction-formula.jar` | Runnable app (tracked) |
| `run.bat` / `build.bat` / `build.ps1` | Launch / rebuild |
| `.gitignore` | Ignores `src/`, most of `build/`, keeps the JAR |
| `docs/w-only-flight-strategy.md` | Flight control design |
| `НАСТРОЙКА.md` | How to tweak colors/constants when you have local sources |

Local (not in git): `src/main/java`, `src/test/java`, and other build outputs under `build/`.

## Model (one line)

Interaction is **W = Q·N** with `Q ∝ 1/r²` and charge/mass from volume density (`N = V·q`). Extras in the UI (chamber wall, dipoles, impermeability, KUO options) are documented in the in-app help (**F1**).
