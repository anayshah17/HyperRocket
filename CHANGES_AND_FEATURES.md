# Changes From Base OpenRocket & Added Features

This document summarizes everything this fork adds on top of stock OpenRocket.
Stock OpenRocket simulates model-rocket flight using the Barrowman aerodynamics
method and RK4/RK6 6-DOF integration. This fork extends it with **structural,
thermal, and recovery failure modeling**, **real-world weather/terrain data**,
and an **animated 3D flight replay**.

Branch: `feature/failure-sim-and-3d-replay`
Scope: 39 files changed, ~5,300 lines added, 15 new source/test files.

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
  the `RECOVERY_DEVICE_DEPLOYMENT` event; always on.
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

- **`gui/simulation/SimulationReplayPanel.java`** *(new, ~1,300 lines)* — JOGL
  `GLJPanel` with orbit camera, ground plane/grid, flight-path polyline, staged
  rocket body, parachute, play/pause/speed controls, and a scrub slider.
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

---

## Cross-Cutting Core Changes

- **`simulation/FlightEvent.java`** — new `FlightEvent.Type` values for the
  failure modes above.
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
| `RecoveryIntegrityListener` | Yes | Shroud-line strength & deployment velocity at deployment |
| `MisalignmentListener` | Yes | Applies angular/radial offset forces every step |
| `StructuralFailureListener` | Toggle | Aero + thrust loads vs material strength |
| `ThermalSimulationListener` | Toggle | Heat transfer vs melt / ignition thresholds |

## Tests Added

- `material/MaterialPersistenceTest.java` — save/load of new material properties.
- `simulation/listeners/FailureListenerTest.java` — structural & thermal failures.
- `simulation/listeners/Phase3ListenerTest.java` — recovery & misalignment.
- `core/util/WeatherFetcherTest.java` — weather parsing.

> Note: 3 pre-existing `ExampleFilesTest` preset-match failures are baseline
> (component-database submodule), not regressions from this work.
