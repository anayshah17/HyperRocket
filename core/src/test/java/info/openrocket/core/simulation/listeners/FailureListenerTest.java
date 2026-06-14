package info.openrocket.core.simulation.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.material.Material;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BondJoint;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.TestRockets;

/**
 * Tests for StructuralFailureListener, ThermalSimulationListener, and BondJoint.
 */
public class FailureListenerTest extends BaseTestCase {

    // ---- BondJoint unit tests ----

    @Test
    public void testBondJointEffectiveStrength() {
        // 10 MPa shear strength, 100 cm² area, 0.8 quality
        BondJoint joint = new BondJoint(BondJoint.BondType.EPOXY, 0.01, 10e6, 0.8);
        double expected = 10e6 * 0.8 * 0.01; // = 80_000 N
        assertEquals(expected, joint.getEffectiveStrength(), 1.0);
    }

    @Test
    public void testBondJointQualityFactorClamped() {
        BondJoint joint = new BondJoint(BondJoint.BondType.CA, 0.005, 5e6, 1.5); // quality > 1 → clamp to 1
        assertEquals(1.0, joint.getQualityFactor(), 1e-9);

        joint.setQualityFactor(-0.1); // negative → clamp to 0
        assertEquals(0.0, joint.getQualityFactor(), 1e-9);
        assertEquals(0.0, joint.getEffectiveStrength(), 1e-9);
    }

    @Test
    public void testBondJointTypes() {
        for (BondJoint.BondType type : BondJoint.BondType.values()) {
            BondJoint joint = new BondJoint(type, 0.01, 1e6, 1.0);
            assertEquals(type, joint.getType());
            assertNotNull(type.toString());
        }
    }

    // ---- Warning subclass unit tests ----

    @Test
    public void testStructuralFailureWarningMessage() {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        RocketComponent tube = rocket.getChild(0).getChild(1); // BodyTube
        Warning.StructuralFailure warn = new Warning.StructuralFailure(tube, 50e6);
        String msg = warn.getMessageDescription();
        assertNotNull(msg);
        assertFalse(msg.isEmpty());
        assertTrue(msg.contains("50.0 MPa"), "Expected stress value in message: " + msg);
    }

    @Test
    public void testThermalFailureWarningMessage() {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        RocketComponent tube = rocket.getChild(0).getChild(1);
        Warning.ThermalFailure warn = new Warning.ThermalFailure(tube, 700.0, 600.0);
        String msg = warn.getMessageDescription();
        assertNotNull(msg);
        assertTrue(msg.contains("700") && msg.contains("600"), "Expected temps in message: " + msg);
    }

    @Test
    public void testBondFailureWarningMessage() {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        RocketComponent tube = rocket.getChild(0).getChild(1);
        Warning.BondFailure warn = new Warning.BondFailure(tube, 1234.5);
        String msg = warn.getMessageDescription();
        assertNotNull(msg);
        assertTrue(msg.contains("1234.5"), "Expected force in message: " + msg);
    }

    @Test
    public void testWarningsDoNotReplaceEachOther() {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        RocketComponent tube = rocket.getChild(0).getChild(1);
        Warning.StructuralFailure a = new Warning.StructuralFailure(tube, 10e6);
        Warning.StructuralFailure b = new Warning.StructuralFailure(tube, 20e6);
        assertFalse(a.replaceBy(b));
    }

    // ---- StructuralFailureListener integration tests ----

    /**
     * A material with 1 Pa tensile strength will always fail under thrust.
     * Expects a STRUCTURAL_FAILURE event and a StructuralFailure warning.
     */
    @Test
    public void testStructuralFailureFiresEventOnWeakMaterial() throws SimulationException {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        AxialStage stage = rocket.getStage(0);
        BodyTube tube = (BodyTube) stage.getChild(1);

        // 1 Pa tensile/compressive strength — any thrust will snap it
        Material weakMat = Material.newMaterial(Material.Type.BULK, "WeakMaterial", 100.0, false);
        weakMat.setStrengthProperties(1.0, 1.0, 1.0, 1.0);
        tube.setMaterial(weakMat);

        StructuralFailureListener listener = new StructuralFailureListener();
        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        sim.simulate(listener);

        FlightData data = sim.getSimulatedData();
        assertNotNull(data, "Simulation produced no data");

        FlightDataBranch branch = data.getBranch(0);
        assertNotNull(branch, "No flight data branch");

        List<FlightEvent> events = branch.getEvents();
        boolean hasStructuralFailure = events.stream()
                .anyMatch(e -> e.getType() == FlightEvent.Type.STRUCTURAL_FAILURE);
        assertTrue(hasStructuralFailure,
                "Expected STRUCTURAL_FAILURE event but got: " + events);

        boolean hasWarning = sim.getSimulatedWarnings().stream()
                .anyMatch(w -> w instanceof Warning.StructuralFailure);
        assertTrue(hasWarning, "Expected StructuralFailure warning in simulation");
    }

    /**
     * A material with very high strength (steel-like) must never fail on a small model rocket.
     */
    @Test
    public void testStructuralFailureDoesNotFireOnStrongMaterial() throws SimulationException {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        AxialStage stage = rocket.getStage(0);
        BodyTube tube = (BodyTube) stage.getChild(1);

        // 400 MPa — steel-like, should never fail on a model rocket
        Material strongMat = Material.newMaterial(Material.Type.BULK, "StrongMaterial", 7800.0, false);
        strongMat.setStrengthProperties(400e6, 400e6, 250e6, 250e6);
        tube.setMaterial(strongMat);

        StructuralFailureListener listener = new StructuralFailureListener();
        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        sim.simulate(listener);

        FlightDataBranch branch = sim.getSimulatedData().getBranch(0);
        List<FlightEvent> events = branch.getEvents();
        boolean hasFailure = events.stream()
                .anyMatch(e -> e.getType() == FlightEvent.Type.STRUCTURAL_FAILURE);
        assertFalse(hasFailure, "Did not expect STRUCTURAL_FAILURE on high-strength material");
    }

    // ---- ThermalSimulationListener integration tests ----

    /**
     * A body tube with 350 K melting point (below motor exhaust temp) should trigger THERMAL_FAILURE.
     */
    @Test
    public void testThermalFailureFiresOnLowMeltingPoint() throws SimulationException {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        AxialStage stage = rocket.getStage(0);
        BodyTube tube = (BodyTube) stage.getChild(1);

        // Very low melting point (350 K ≈ 77°C) and sufficient specific heat
        Material meltableMat = Material.newMaterial(Material.Type.BULK, "MeltableMaterial", 100.0, false);
        meltableMat.setThermalProperties(350.0, 0.0, 0.2, 100.0); // melting=350K, cp=100 J/kg·K
        tube.setMaterial(meltableMat);

        ThermalSimulationListener listener = new ThermalSimulationListener();
        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        sim.simulate(listener);

        FlightDataBranch branch = sim.getSimulatedData().getBranch(0);
        assertNotNull(branch);

        List<FlightEvent> events = branch.getEvents();
        boolean hasThermalFailure = events.stream()
                .anyMatch(e -> e.getType() == FlightEvent.Type.THERMAL_FAILURE);
        assertTrue(hasThermalFailure,
                "Expected THERMAL_FAILURE event; got: " + events);
    }

    /**
     * A body tube with a very high melting point (e.g., tungsten: 3695 K) must not
     * trigger a thermal failure on a small model rocket simulation.
     */
    @Test
    public void testThermalFailureDoesNotFireOnHighMeltingPoint() throws SimulationException {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        AxialStage stage = rocket.getStage(0);
        BodyTube tube = (BodyTube) stage.getChild(1);

        // Tungsten-like: melting point 3695 K, well above exhaust temp
        Material tungstenMat = Material.newMaterial(Material.Type.BULK, "TungstenMaterial", 19300.0, false);
        tungstenMat.setThermalProperties(3695.0, 0.0, 174.0, 134.0);
        tube.setMaterial(tungstenMat);

        ThermalSimulationListener listener = new ThermalSimulationListener();
        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        sim.simulate(listener);

        FlightDataBranch branch = sim.getSimulatedData().getBranch(0);
        List<FlightEvent> events = branch.getEvents();
        boolean hasFailure = events.stream()
                .anyMatch(e -> e.getType() == FlightEvent.Type.THERMAL_FAILURE);
        assertFalse(hasFailure, "Did not expect THERMAL_FAILURE on tungsten-like material");
    }

    /**
     * Bond joint with effectively zero strength must fire BOND_FAILURE and COMPONENT_SEPARATION.
     */
    @Test
    public void testBondFailureFiresOnZeroStrengthJoint() throws SimulationException {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        AxialStage stage = rocket.getStage(0);
        BodyTube tube = (BodyTube) stage.getChild(1);

        // Bond area 1 mm², but 0 quality → effective strength = 0 → any load fails it
        BondJoint zeroJoint = new BondJoint(BondJoint.BondType.TAPE, 1e-6, 1e6, 0.0);

        StructuralFailureListener listener = new StructuralFailureListener();
        listener.addBondJoint(tube, zeroJoint);

        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        sim.simulate(listener);

        FlightDataBranch branch = sim.getSimulatedData().getBranch(0);
        List<FlightEvent> events = branch.getEvents();

        boolean hasBondFailure = events.stream()
                .anyMatch(e -> e.getType() == FlightEvent.Type.BOND_FAILURE);
        boolean hasSeparation = events.stream()
                .anyMatch(e -> e.getType() == FlightEvent.Type.COMPONENT_SEPARATION);

        assertTrue(hasBondFailure, "Expected BOND_FAILURE event; got: " + events);
        assertTrue(hasSeparation, "Expected COMPONENT_SEPARATION event; got: " + events);
    }
}
