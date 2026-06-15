# HyperRocket

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

**HyperRocket** is an enhanced fork of the [OpenRocket](https://openrocket.info/)
model-rocket simulator. It keeps everything that makes OpenRocket great — full
six-degree-of-freedom flight simulation using the Barrowman aerodynamics method
and RK4/RK6 numerical integration — and adds a layer of **failure modeling**,
**real-world environmental data**, **more realistic descent physics**, and an
**animated 3D flight replay**.

If OpenRocket answers *"how high and how fast will it go?"*, HyperRocket also
answers *"will it survive the flight, and what will it look like coming down?"*

> HyperRocket is built on OpenRocket, which was created by Sampo Niskanen and
> many contributors. HyperRocket is distributed under the same GNU GPL v3 license
> and is fully compatible with the `.ork` design file format.

--------

## ✨ What HyperRocket Adds

| # | Feature | What it does |
|---|---------|--------------|
| 1 | **Structural failure simulation** | Fails components when aero + thrust loads exceed material strength |
| 2 | **Thermal / aero-heating simulation** | Warns when surfaces melt or auto-ignite from air friction |
| 3 | **Bond / joint strength modeling** | Models glue/tape/screw joints and fails them under shear |
| 4 | **Recovery-device integrity** | Destroys a parachute that deploys too fast or overloads its shroud lines |
| 5 | **Component misalignment** | Injects build imperfections (cocked fins/nose) that bend the flight path |
| 6 | **Animated 3D flight replay** | Plays back the whole flight in 3D, including a realistic recovery scene |
| 7 | **Terrain rendering** | Draws real desert terrain and a launch site beneath the rocket |
| 8 | **Real weather import** | Pulls live atmospheric/wind data into the multi-level wind model |
| 9 | **Extended material database** | Realistic strength/thermal defaults so failures fire on stock materials |
| 10 | **Descent physics & calm-wind realism** | Canopy inflation transient + vertical descent in dead-calm air |
| 11 | **Mission Control telemetry** | Live, replay-synced telemetry plots + event timeline beside the 3D view |

A detailed, file-by-file breakdown lives in
[CHANGES_AND_FEATURES.md](CHANGES_AND_FEATURES.md).

--------

## ⚡ Optimizations & Physics Improvements

HyperRocket isn't only new features — it also fixes and sharpens the core
simulation and rendering:

- **Calm-wind drift fixed.** Stock pink-noise wind applies turbulence as a
  fraction of mean speed, which at near-zero wind behaves like a phantom steady
  breeze — a slow parachute integrates it into metres of one-directional drift.
  HyperRocket fades turbulence to zero below a 0.5 m/s mean wind (via a Hermite
  smoothstep), so "0 wind" descends straight down. Flights with mean ≥ 0.5 m/s
  are **bit-for-bit unchanged**.
- **Canopy inflation transient.** Parachute drag now ramps up over ~0.4 s after
  deployment instead of snapping on instantaneously, removing the unphysical
  velocity discontinuity at ejection.
- **Realistic recovery in 3D.** Under canopy the nose cone pops off and dangles
  on the shock cord while the airframe swings beneath the parachute — each part
  moving on its own decaying-pendulum phase rather than as a rigid body.
- **Zero-allocation render loop.** The 3D replay reuses a single GLU quadric and
  helper objects instead of allocating per frame, keeping playback smooth.
- **Full state capture.** Position (X/Y), Euler tilt/heading, and the complete
  orientation quaternion are now written to flight data every step — the data
  the 3D replay (and any external tool) needs for accurate playback.

--------

## 🚀 How to Use the New Features

### 1 & 2 — Structural & Thermal Failure Simulation
Both are toggles in the simulation editor:
1. Open a simulation → **Edit simulation** → **Simulation options** tab.
2. Under the **Failure Simulation** panel, tick **Enable structural failure**
   and/or **Enable thermal simulation**.
3. Run the simulation. If loads exceed a component's strength, or a surface
   exceeds its melting / auto-ignition temperature, a warning appears in the
   **Warnings** panel and a failure event is added to the flight.

Strength and thermal limits come from each component's material. Stock materials
ship with realistic defaults (feature #9), so failures fire out of the box. To
tune them, edit a component's material → **Custom** and use the **Structural
Properties** and **Thermal Properties** panels.

### 3 — Bond / Joint Strength
1. Select a component in the design tree → its config dialog → **Bond Joint** tab.
2. Pick a joint type (Epoxy, CA, Wood glue, Tape, Screws, Weld), then set the
   **bond area (cm²)**, **shear strength (MPa)**, **quality factor (0–1)**, and a
   **temperature limit**.
3. Enable **structural failure** (see above). During flight the joint fails if
   the shear force exceeds `shearStrength × bondArea × qualityFactor`.

Bond joints are saved into your `.ork` file, so they persist across sessions.

### 4 — Recovery-Device Integrity
1. Select a **parachute** or **streamer** → its config dialog → **Recovery
   Integrity** panel.
2. Set the **shroud-line strength (N)**, **max deployment velocity (m/s)**, and
   **opening-shock factor**.
3. If the device deploys above its velocity limit or its lines are overloaded, it
   **fails destructively** — deployment is vetoed, the rocket continues a
   ballistic descent, and a `PARACHUTE_FAILURE` event is logged. Leaving the
   limits unset keeps stock behavior.

### 5 — Component Misalignment
1. Select any component → its config dialog → **Misalignment** tab.
2. Dial in an **angular offset** (e.g. a cocked nose cone, slider 0–5°) and/or a
   **radial offset** (slider 0–50 mm).
3. The offsets inject a persistent force/torque every step, so an imperfect build
   curves or coning during flight — no toggle needed, it's always active.

### 6 — 3D Flight Replay
1. Run a simulation.
2. In the **Simulations** table, select it and click **Replay 3D**.
3. The replay window opens with an orbit camera (drag to rotate, scroll to zoom),
   ground/terrain, the flight-path trace, and **play / pause / speed** controls
   plus a scrub slider. Watch staging, ejection, and the recovery descent.

### 11 — Mission Control Telemetry
When you open the 3D replay, a **Mission Control** panel appears on the right,
locked to the replay clock — a miniature mission-control station for analysing the
flight:
1. Stacked, time-synchronised plots (Altitude, Velocity, Acceleration by default)
   sit beside the 3D view, with live numeric read-outs and the replay time.
2. A red cursor sweeps across all plots as the replay plays; **left-drag** any plot
   to scrub the replay directly (the rocket, slider and read-outs all follow).
3. **Mouse-wheel** zooms the timeline, **shift-drag** pans, **double-click** resets.
4. Every flight event — including `STRUCTURAL_FAILURE`, `THERMAL_FAILURE`,
   `BOND_FAILURE` and `PARACHUTE_FAILURE` — is a vertical marker; **hover** it for
   the event name, source component and timestamp.
5. The panel is **expandable** — use **Hide/Show telemetry** (or the split's
   one-touch arrows) to collapse it — and **Fullscreen** blows the 3D viewer up to
   the whole screen (press **Esc** to exit).

This lets you ask *why* a flight behaved as it did: line up the moment you see in
3D with the telemetry, and read what happened just before a parachute or bond
failure.

### 7 — Terrain Rendering
Terrain is generated automatically for the launch site and drawn beneath the
rocket in the design-tab 3D view and in the 3D replay — no setup required.

### 8 — Real Weather Import
In **Simulation options**, use the weather-import control to fetch live
atmospheric/wind data for your launch coordinates; it populates the multi-level
wind model (which interpolates speed/direction/turbulence across altitude).

### 9 — Extended Material Database
Automatic. Stock materials now carry realistic strength and thermal properties,
so structural/thermal warnings are meaningful without hand-editing every part.

### 10 — Descent Physics
Automatic and always on. You'll notice straight-down descent in calm air and a
smooth (rather than instantaneous) parachute opening.

--------

## 🛠️ Build & Run

Requires **Java 17** and Gradle (a wrapper is included). On Windows use
`gradlew.bat` instead of `./gradlew`.

**First-time setup** — the component database is a git submodule:
```bash
git submodule init
git submodule update --remote
```

| Task | Command |
|------|---------|
| Run the app | `./gradlew run` |
| Build (compile + test + checks) | `./gradlew build` |
| Run all tests + static analysis | `./gradlew check` |
| Core tests only | `./gradlew :core:test` |
| Swing tests only | `./gradlew :swing:test` |
| Build distributable JAR | `./gradlew dist` |

The project has two Gradle modules: **`core/`** (pure-Java physics, simulation,
and file I/O — usable as a library) and **`swing/`** (the desktop UI, built on
Java Swing + JOGL/OpenGL).

--------

## 📂 Documentation

- [CHANGES_AND_FEATURES.md](CHANGES_AND_FEATURES.md) — full, file-level summary of
  everything HyperRocket adds on top of OpenRocket.
- [CLAUDE.md](CLAUDE.md) — architecture notes and developer guidance.
- Underlying OpenRocket docs: [openrocket.readthedocs.io](https://openrocket.readthedocs.io/en/latest/).

--------

## 🙏 Credits

HyperRocket stands on the shoulders of **OpenRocket** and its community.
OpenRocket was originally developed by **Sampo Niskanen** and a large group of
contributors and translators — see the full list on the
[OpenRocket contributors page](https://github.com/openrocket/openrocket/graphs/contributors).

## 📜 License

HyperRocket, like OpenRocket, is open-source under the
[GNU GPL v3](https://www.gnu.org/licenses/gpl-3.0.en.html) license. Feel free to
use, study, and extend it.
