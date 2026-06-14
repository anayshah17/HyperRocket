package info.openrocket.core.simulation.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.TestRockets;

/**
 * Tests for Phase 3: quaternion data, RecoveryIntegrityListener, MisalignmentListener.
 */
public class Phase3ListenerTest extends BaseTestCase {

    // ---- Quaternion storage tests ----

    @Test
    public void testQuaternionComponentsStoredEachStep() throws SimulationException {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        sim.simulate();

        FlightDataBranch branch = sim.getSimulatedData().getBranch(0);
        assertNotNull(branch);

        List<Double> qw = branch.get(FlightDataType.TYPE_ORIENTATION_QW);
        List<Double> qx = branch.get(FlightDataType.TYPE_ORIENTATION_QX);
        List<Double> qy = branch.get(FlightDataType.TYPE_ORIENTATION_QY);
        List<Double> qz = branch.get(FlightDataType.TYPE_ORIENTATION_QZ);

        assertNotNull(qw, "TYPE_ORIENTATION_QW not stored");
        assertNotNull(qx, "TYPE_ORIENTATION_QX not stored");
        assertNotNull(qy, "TYPE_ORIENTATION_QY not stored");
        assertNotNull(qz, "TYPE_ORIENTATION_QZ not stored");

        assertFalse(qw.isEmpty(), "QW list is empty");
        assertFalse(qx.isEmpty(), "QX list is empty");

        // At launch the rocket is vertical — QW should be near 1, QX/QY/QZ near 0
        double firstQw = qw.get(0);
        assertTrue(Math.abs(firstQw) > 0.9, "Initial QW should be close to 1.0 for vertical flight; was " + firstQw);

        // Quaternion magnitude should be approximately 1.0 at every step
        for (int i = 0; i < qw.size(); i++) {
            double mag = Math.sqrt(qw.get(i) * qw.get(i)
                    + qx.get(i) * qx.get(i)
                    + qy.get(i) * qy.get(i)
                    + qz.get(i) * qz.get(i));
            assertEquals(1.0, mag, 0.01, "Quaternion magnitude not 1.0 at step " + i);
        }
    }

    // ---- RecoveryDevice integrity field tests ----

    @Test
    public void testRecoveryDeviceDefaultFields() {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        // find the parachute
        Parachute chute = findParachute(rocket);
        assertNotNull(chute, "Test rocket has no parachute");

        assertEquals(0.0, chute.getShroudLineStrength(), 1e-9);
        assertEquals(0.0, chute.getMaxDeploymentVelocity(), 1e-9);
        assertEquals(1.0, chute.getOpeningShockFactor(), 1e-9);
    }

    @Test
    public void testRecoveryDeviceSettersClampNegative() {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        Parachute chute = findParachute(rocket);
        assertNotNull(chute);

        chute.setShroudLineStrength(-5.0);
        assertEquals(0.0, chute.getShroudLineStrength(), 1e-9, "Negative shroud strength should clamp to 0");

        chute.setMaxDeploymentVelocity(-10.0);
        assertEquals(0.0, chute.getMaxDeploymentVelocity(), 1e-9, "Negative velocity should clamp to 0");

        chute.setOpeningShockFactor(-1.0);
        assertEquals(0.0, chute.getOpeningShockFactor(), 1e-9, "Negative shock factor should clamp to 0");
    }

    @Test
    public void testRecoveryIntegrityListenerNoFailureOnNormalDeploy() throws SimulationException {
        // A very high strength parachute — should never fail on a small model rocket
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        Parachute chute = findParachute(rocket);
        assertNotNull(chute);

        chute.setShroudLineStrength(10000.0); // 10 kN per line — enormous
        chute.setMaxDeploymentVelocity(1000.0); // 1000 m/s limit — impossible to exceed

        RecoveryIntegrityListener listener = new RecoveryIntegrityListener();
        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        sim.simulate(listener);

        FlightDataBranch branch = sim.getSimulatedData().getBranch(0);
        assertNotNull(branch);

        boolean hasParachuteFailure = branch.getEvents().stream()
                .anyMatch(e -> e.getType() == FlightEvent.Type.PARACHUTE_FAILURE);
        assertFalse(hasParachuteFailure,
                "Should NOT fail with enormous shroud strength and huge velocity limit");
    }

    @Test
    public void testRecoveryIntegrityListenerFiresOnLowVelocityLimit() throws SimulationException {
        // Velocity limit of 1 m/s — practically any deploy will exceed this
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        Parachute chute = findParachute(rocket);
        assertNotNull(chute);

        chute.setMaxDeploymentVelocity(1.0); // 1 m/s — will certainly be exceeded

        RecoveryIntegrityListener listener = new RecoveryIntegrityListener();
        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        sim.simulate(listener);

        FlightDataBranch branch = sim.getSimulatedData().getBranch(0);
        assertNotNull(branch);

        boolean hasParachuteFailure = branch.getEvents().stream()
                .anyMatch(e -> e.getType() == FlightEvent.Type.PARACHUTE_FAILURE);
        assertTrue(hasParachuteFailure,
                "Expected PARACHUTE_FAILURE when velocity limit is 1 m/s; events: " + branch.getEvents());

        boolean hasWarning = sim.getSimulatedWarnings().stream()
                .anyMatch(w -> w instanceof Warning.ParachuteFailure);
        assertTrue(hasWarning, "Expected ParachuteFailure warning");
    }

    // ---- RocketComponent misalignment field tests ----

    @Test
    public void testRocketComponentMisalignmentDefaults() {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        AxialStage stage = rocket.getStage(0);
        BodyTube tube = (BodyTube) stage.getChild(1);

        assertEquals(0.0, tube.getAngularOffset(), 1e-9, "Default angular offset should be 0");
        assertEquals(0.0, tube.getRadialOffset(), 1e-9, "Default radial offset should be 0");
    }

    @Test
    public void testRocketComponentMisalignmentSettersWork() {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        AxialStage stage = rocket.getStage(0);
        BodyTube tube = (BodyTube) stage.getChild(1);

        tube.setAngularOffset(0.05); // ~3 degrees
        assertEquals(0.05, tube.getAngularOffset(), 1e-9);

        tube.setAngularOffset(-0.05); // negative allowed (direction of tilt)
        assertEquals(-0.05, tube.getAngularOffset(), 1e-9);

        tube.setRadialOffset(0.002); // 2 mm off-center
        assertEquals(0.002, tube.getRadialOffset(), 1e-9);

        tube.setRadialOffset(-0.001); // negative → clamped to 0
        assertEquals(0.0, tube.getRadialOffset(), 1e-9, "Negative radial offset should clamp to 0");
    }

    @Test
    public void testMisalignmentListenerWarnsOnMisalignedComponent() throws SimulationException {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        AxialStage stage = rocket.getStage(0);
        BodyTube tube = (BodyTube) stage.getChild(1);

        // Significant angular misalignment — large enough to generate side force > 1 N
        tube.setAngularOffset(Math.toRadians(10.0)); // 10 degrees

        MisalignmentListener listener = new MisalignmentListener();
        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        sim.simulate(listener);

        FlightData data = sim.getSimulatedData();
        assertNotNull(data, "Simulation produced no data");

        // The listener issues Warning.Other with a "Component misalignment" message
        boolean hasMisalignmentWarning = sim.getSimulatedWarnings().stream()
                .anyMatch(w -> w instanceof Warning.Other
                        && w.getMessageDescription().contains("misalignment"));
        assertTrue(hasMisalignmentWarning,
                "Expected a misalignment Warning.Other; got: " + sim.getSimulatedWarnings());
    }

    @Test
    public void testMisalignmentListenerSilentWhenNoMisalignment() throws SimulationException {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        // All offsets default to 0 — no warning expected

        MisalignmentListener listener = new MisalignmentListener();
        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        sim.simulate(listener);

        boolean hasMisalignmentWarning = sim.getSimulatedWarnings().stream()
                .anyMatch(w -> w instanceof Warning.Other
                        && w.getMessageDescription().contains("misalignment"));
        assertFalse(hasMisalignmentWarning,
                "Should not warn when all components are perfectly aligned");
    }

    // ---- Helpers ----

    private static Parachute findParachute(Rocket rocket) {
        for (RocketComponent comp : rocket) {
            if (comp instanceof Parachute) {
                return (Parachute) comp;
            }
        }
        return null;
    }
}
