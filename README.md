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
| Земля–Луна | Loads **immediately** on select. Earth `N ≈ 1e13`; Moon mass = Earth × editable share (default `1/81.3`); astronomical length scale |
| Солнечная система | Loads **immediately**. Sun + planets + major moons + main belt (~2.2–3.3 AU) + Kuiper (~30–48 AU) on full astronomical scale (`AU_RE ≈ 23455` R⊕). Belt masses `1e1…1e4` m_e (≪ planets) |
| Песочница | Loads **immediately**. Empty scene |
| **мелкие фракции** | Last in the list; does **not** replace the scene. Count chooser **100 / 500 / 2000 / 5000** (default 2000); **применить** **appends** asteroid-like masses (`~1e3…1e8` m_e) on top of the current scenario and turns **непроницаемые** on (you can turn it off afterward) |

**Непроницаемые** lives on this tab (soft contact shell). It is not under **Вид**.

### Other tabs (short)

- **Частицы** — add/remove p, n, e, atom, dipole, custom mass; `+ рандом`; spawn mode (orbit / rest / throw)
- **Тяготение** — direct W = Q·N vs complex (КУО); G presets
- **Расчёт** — time speed, CPU/GPU path, shaders
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
