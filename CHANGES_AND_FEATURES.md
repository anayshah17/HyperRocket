# HyperRocket — Changes From Base OpenRocket & Added Features

This document summarizes everything **HyperRocket** adds on top of stock
OpenRocket. Stock OpenRocket simulates model-rocket flight using the Barrowman
aerodynamics method and RK4/RK6 6-DOF integration. HyperRocket extends it with
**structural, thermal, and recovery failure modeling**, **real-world
weather/terrain data**, **more realistic descent physics**, and an **animated 3D
flight replay**.

Branch: `feature/failure-sim-and-3d-replay`
Scope: 48 files changed, ~6,500 lines added, 16 new source/test files.

---

## At a Glance

| # | Feature | Where | Toggle |
|---|---------|-------|--------|
| 1 | Structural failure simulation | core listener + Material strength | `SimulationOptions.enableStructuralFailure` |
| 2 | Thermal / aero-heating simulation | core listener + Material thermal props | `SimulationOptions.enableThermalSimulation` |
| 3 | Bond / joint strength modeling | `BondJoint` + component "Bond Joint" tab | Via Structural Failure |
| 4 | Recovery-device integrity | core listener + `RecoveryDevice` fields | Always on |
| 5 | Component misalignment | core listener + `RocketComponent` offsets | Always on |
| 6 | 3D flight replay | swing `SimulationReplayPanel/Dialog` | "Replay 3D" button |
| 7 | Terrain rendering | `TerrainRenderer` + `TerrainFetcher` | Design 3D / replay |
| 8 | Real weather import | `WeatherFetcher` | Simulation options |
| 9 | Extended material database | `MaterialPhysicalDefaults` | Automatic |
| 10 | Descent physics & calm-wind realism | `BasicLandingStepper`, `PinkNoiseWindModel` | Automatic |
| 11 | Mission Control telemetry | swing `MissionControlTelemetryPanel` | Always on in 3D replay |

---

## 1. Structural Failure Simulation

Compares aerodynamic + thrust loads against material strength limits every step.

- **`simulation/listeners/StructuralFailureListener.java`** *(new)* — at each
  `postStep`, computes loads and fires a warning event when a component exceeds
  its tensile / compressive / shear / yield strength.
- **`material/Material.java`** — extended from 2 to **10 properties**. Added
  `tensileStrength`, `compressiveStrength`, `shearStrength`, `yieldStrength`
  (Pa) plus convenience setter `setStrengthProperties(...)`.
- **UI — `gui/dialogs/CustomMaterialDialog.java`** — "Structural Properties"
  panel with four strength spinners (Pa/MPa/GPa/ksi selector).
- **Toggle** — `SimulationOptions.enableStructuralFailure`; checkbox in
  `SimulationOptionsPanel` under "Failure Simulation"; listener conditionally
  added in `Simulation.simulate()`.

## 2. Thermal Simulation

Models aerodynamic heating and warns on melt / auto-ignition.

- **`simulation/listeners/ThermalSimulationListener.java`** *(new)* — computes
  per-step heat transfer; warns when component temperature exceeds melting point
  or auto-ignition temperature.
- **`material/Material.java`** — added `meltingPoint`, `autoIgnitionTemp` (K),
  `thermalConductivity` (W/m·K), `specificHeat` (J/kg·K) with
  `setThermalProperties(...)`.
- **UI — `CustomMaterialDialog`** — "Thermal Properties" panel with temperature
  spinners (K/°C) plus conductivity and specific-heat spinners.
- **Toggle** — `SimulationOptions.enableThermalSimulation`.

## 3. Bond / Joint Strength

- **`rocketcomponent/BondJoint.java`** *(new)* — models adhesive/mechanical
  joints per component. `BondType` enum (EPOXY, CA, WOOD_GLUE, SCREWS, WELD),
  `bondArea` (m²), `shearStrength` (Pa), `qualityFactor` (0–1),
  `temperatureLimit` (K). Fails when shear force > shearStrength × bondArea ×
  qualityFactor.
- **`rocketcomponent/RocketComponent.java`** — each component carries a
  `bondJoint` (getter/setter, copied in `copyFrom`).
- **UI — `RocketComponentConfig.java`** — "Bond Joint" tab (`bondJointTab()`)
  with joint-type combo, bond area (cm²), shear strength (MPa), quality factor,
  and temperature-limit spinners.
- **Wiring** — `Simulation.simulate()` registers each active component's joint
  into `StructuralFailureListener` (only when structural failure is enabled and
  the joint has non-zero area and strength); the listener checks shear loads in
  `checkBondJoint()`.
- **Persistence** — bond joints survive `.ork` save/load: non-default joints are
  written as a `<bondjoint>` element by `RocketComponentSaver`, and parsed back
  by `BondJointSetter` (registered in `DocumentConfig`). Covered by
  `MaterialPersistenceTest`.

## 4. Recovery-Device Integrity

- **`simulation/listeners/RecoveryIntegrityListener.java`** *(new)* — checks at
  the `RECOVERY_DEVICE_DEPLOYMENT` event; always on. A chute that exceeds its
  user-set deployment-velocity or shroud-line limit now **fails destructively**:
  the deployment is vetoed (no drag → ballistic descent) and a
  `PARACHUTE_FAILURE` event fires. Stock chutes with no limits set are
  unaffected.
- **`rocketcomponent/RecoveryDevice.java`** — added `shroudLineStrength` (N),
  `maxDeploymentVelocity` (m/s), `openingShockFactor`, with `copyWithOriginalID`
  support.
- **UI — `ParachuteConfig.java` / `StreamerConfig.java`** — "Recovery Integrity"
  panel with three spinners each (force / velocity / coefficient).

## 5. Component Misalignment

- **`simulation/listeners/MisalignmentListener.java`** *(new)* — injects
  persistent torque/force each step proportional to build offsets; always on.
- **`rocketcomponent/RocketComponent.java`** — added `angularOffset` (rad) and
  `radialOffset` (m) with getters/setters and `copyFrom` support.
- **UI — `RocketComponentConfig.java`** — "Misalignment" tab with an angle
  spinner (slider 0–5°) and a length spinner (slider 0–50 mm).

## 6. 3D Flight Replay

Animated playback of a completed simulation.

- **`gui/simulation/SimulationReplayPanel.java`** *(new, ~1,600 lines)* — JOGL
  `GLJPanel` with orbit camera, ground plane/grid, flight-path polyline, staged
  rocket body, parachute, play/pause/speed controls, and a scrub slider.
- **Realistic recovery scene** — under canopy the nose cone pops off and dangles
  on the shock cord while the airframe hangs from the parachute, each part
  swinging loosely on its own phase (a decaying multi-pendulum) rather than as a
  rigid body. The hanging airframe reuses the detailed rocket model with the
  nose cone hidden (new `RocketRenderer.render(...)` ignore-set overload), so its
  shape stays continuous with ascent instead of swapping in a plain tube. The
  render loop reuses a single `GLUquadric`, allocating nothing per frame.
- **`gui/simulation/SimulationReplayDialog.java`** *(new)* — 900×700 modeless
  dialog wrapping the panel.
- **`gui/main/SimulationPanel.java`** — "Replay 3D" button + `Replay3DAction`.
- **Data plumbing** — `SimulationStatus.storeData()` now saves `TYPE_POSITION_X/Y`,
  `TYPE_ORIENTATION_THETA/PHI`, and quaternion `TYPE_ORIENTATION_QW/QX/QY/QZ`
  every step; the new `FlightDataType` entries are registered in
  `FlightDataType.java`.

## 7. Terrain Rendering

- **`gui/figure3d/TerrainRenderer.java`** *(new)* — renders DEM-derived terrain
  beneath the rocket, consumed by the 3D viewer/replay.
- **`core/util/TerrainFetcher.java`** *(new)* — fetches/derives elevation tiles
  for the launch site.
- **`gui/figure3d/RocketFigure3d.java`** — hooks terrain into the design-tab 3D view.

## 8. Real Weather Import

- **`core/util/WeatherFetcher.java`** *(new, ~520 lines)* — fetches sounding /
  forecast data to populate the multi-level wind model from the launch site,
  with tests in `WeatherFetcherTest.java`.
- Surfaced through `SimulationOptionsPanel`.

## 9. Extended Material Database

- **`core/material/MaterialPhysicalDefaults.java`** *(new)* — realistic
  strength/thermal defaults so failure and thermal warnings fire on stock
  materials.
- **`core/database/Databases.java`** — loads the extended defaults.
- **`core/file/openrocket/importt/MaterialSetter.java`** + **`savers/RocketComponentSaver.java`**
  — persist the new material properties in `.ork` files
  (tested in `MaterialPersistenceTest.java`).
- **`unit/UnitGroup.java`** — `UNITS_SHEAR_MODULUS` gains an MPa unit so strength
  values (10–1000 MPa) display sensibly.

## 10. Descent Physics & Calm-Wind Realism

Makes recovery descent behave physically; all changes are automatic and leave
normal (non-calm) ascent flights bit-for-bit unchanged.

- **`models/wind/PinkNoiseWindModel.java`** — fades turbulence to zero as the
  mean wind approaches calm (below a 0.5 m/s threshold, via a Hermite
  `smoothstep`). Pink noise has heavy low-frequency power, so a non-zero standard
  deviation at near-zero mean speed behaves like a phantom steady breeze that a
  slow parachute descent integrates into a large, always-same-direction drift.
  Flights with mean ≥ 0.5 m/s are unaffected.
- **`simulation/BasicLandingStepper.java`** — models the canopy **inflation
  transient**: drag ramps up over ~0.4 s after deployment instead of jumping
  instantaneously. Per-device deploy time is tracked in `SimulationStatus`.
- **`simulation/SimulationStatus.java`** — records per-device deployment time to
  drive the inflation ramp and the destructive-failure check.

## 11. Mission Control Telemetry

Turns the 3D replay from "watching a rocket fly" into "analysing a mission": a
telemetry panel docked beside the 3D view, locked to the replay clock.

- **`gui/simulation/MissionControlTelemetryPanel.java`** *(new)* — a stack of
  compact, time-synchronised mini plots built on OpenRocket's existing JFreeChart
  stack (`CombinedDomainXYPlot` with one subplot per channel sharing a single
  time axis). Default channels are **Altitude**, **Velocity** and
  **Acceleration**; the channel list is just a `List<FlightDataType>`, so adding a
  new telemetry trace is a one-line change.
- **Pulls data straight from `FlightDataBranch`** (branch 0) — nothing is
  recomputed. Live numeric read-outs show the value at the cursor for every
  channel plus the replay time.
- **Two-way replay sync.** `SimulationReplayPanel.refreshFrame()` pushes the
  current time into `setTime(...)`, advancing a cursor `ValueMarker` across all
  plots; conversely dragging/clicking a plot calls back through a
  `ReplayController` (`SimulationReplayPanel.seekToTime(...)`) to move the rocket,
  the slider, the read-outs and the highlighted events. Speed changes and pausing
  don't affect cursor accuracy (the cursor tracks the frame's actual time).
- **Event timeline.** Every `FlightEvent` across all branches is drawn as a
  colour-coded vertical marker (reusing the `FlightEvent` objects directly),
  including the HyperRocket failure events `STRUCTURAL_FAILURE`,
  `THERMAL_FAILURE`, `BOND_FAILURE` and `PARACHUTE_FAILURE`. Hovering a marker
  shows a tooltip with the event name, the source component and its timestamp.
- **Timeline interaction.** Mouse-wheel zooms the time axis (all plots together),
  shift-drag pans, double-click resets the zoom, and left-drag scrubs the replay.
- **Wiring — `gui/simulation/SimulationReplayPanel.java`** — the 3D view, stats
  sidebar and controls move into a left `replayArea`; a `JSplitPane` puts the
  telemetry panel on the right. `SimulationReplayDialog` is widened to 1280×760.
- **Expandable panel & fullscreen.** A "Hide/Show telemetry" button (plus the
  split's one-touch arrows) collapses or expands Mission Control, and a
  "Fullscreen" button makes the 3D viewer take the whole screen (exclusive
  fullscreen where supported, otherwise a maximised borderless window; **Esc**
  exits). Entering fullscreen auto-collapses telemetry and restores it on exit.

---

## Cross-Cutting Core Changes

- **`simulation/FlightEvent.java`** — new `FlightEvent.Type` values for the
  failure modes above, including `PARACHUTE_FAILURE` for destructive recovery
  failure.
- **`logging/Warning.java`** — new `Warning` subclasses surfaced in the warnings
  panel for each failure type.
- **`document/Simulation.java`** — registers the four custom listeners in
  `simulate()`: `RecoveryIntegrityListener` and `MisalignmentListener` always on;
  `StructuralFailureListener` and `ThermalSimulationListener` toggled via
  `SimulationOptions`.
- **`simulation/BasicEventSimulationEngine.java`**, **`motor/ThrustCurveMotor.java`**,
  **`l10n/messages.properties`** (+81 strings) — supporting plumbing/localization.

## Listener Summary

| Listener | Always on? | What it checks |
|----------|-----------|----------------|
| `RecoveryIntegrityListener` | Yes | Shroud-line strength & deployment velocity at deployment; vetoes deployment (destructive failure) when exceeded |
| `MisalignmentListener` | Yes | Applies angular/radial offset forces every step |
| `StructuralFailureListener` | Toggle | Aero + thrust loads vs material strength |
| `ThermalSimulationListener` | Toggle | Heat transfer vs melt / ignition thresholds |

## Tests Added

- `material/MaterialPersistenceTest.java` — save/load of new material properties.
- `simulation/listeners/FailureListenerTest.java` — structural & thermal failures.
- `simulation/listeners/Phase3ListenerTest.java` — recovery & misalignment.
- `core/util/WeatherFetcherTest.java` — weather parsing.
- `simulation/RecoveryRealismTest.java` — calm vertical descent, no drift at
  zero-mean turbulence, and destructive parachute failure.

> Note: 3 pre-existing `ExampleFilesTest` preset-match failures are baseline
> (component-database submodule), not regressions from this work.
