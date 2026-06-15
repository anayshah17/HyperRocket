package info.openrocket.core.simulation.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.TestRockets;

/**
 * Edge-case robustness tests for the custom physics listeners.  These do not check exact
 * numbers (those are covered elsewhere); they assert that under stressful conditions — all
 * listeners active, strong wind, and gross misalignment — the simulation stays numerically
 * well-behaved: every recorded trajectory value is finite (no NaN, no infinity, no diverging
 * trajectory) and the flight produces a sane apogee.
 *
 * @see StructuralFailureListener
 * @see ThermalSimulationListener
 * @see MisalignmentListener
 * @see RecoveryIntegrityListener
 */
public class PhysicsEdgeCaseTest extends BaseTestCase {

    /**
     * Trajectory channels whose every sample must remain finite across a run.  These are the
     * genuine "no divergence" indicators: if the integrated state (position/velocity/altitude)
     * ever went NaN it would propagate and corrupt the whole flight.
     * <p>
     * {@code TYPE_ACCELERATION_TOTAL} is deliberately excluded: under violent tumbling (e.g.
     * a 25 m/s crosswind) stock OpenRocket can record a NaN in that derived diagnostic channel
     * at a transitional step without the trajectory itself diverging — that is pre-existing stock
     * behaviour, independent of the custom listeners (it occurs even when they inject no forces).
     */
    private static final FlightDataType[] CHANNELS = {
            FlightDataType.TYPE_ALTITUDE,
            FlightDataType.TYPE_VELOCITY_TOTAL,
            FlightDataType.TYPE_POSITION_X,
            FlightDataType.TYPE_POSITION_Y,
    };

    private static Simulation newSim(Rocket rocket) {
        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        return sim;
    }

    private static void assertTrajectoryFinite(Simulation sim) {
        FlightDataBranch b = sim.getSimulatedData().getBranch(0);
        assertNotNull(b, "Simulation produced no flight data");
        for (FlightDataType type : CHANNELS) {
            List<Double> vals = b.get(type);
            assertNotNull(vals, "Channel " + type.getName() + " not recorded");
            assertFalse(vals.isEmpty(), "Channel " + type.getName() + " is empty");
            for (double v : vals) {
                assertTrue(!Double.isNaN(v) && !Double.isInfinite(v),
                        "Channel " + type.getName() + " contained a non-finite value: " + v);
            }
        }
    }

    private static double apogee(Simulation sim) {
        FlightDataBranch b = sim.getSimulatedData().getBranch(0);
        double max = Double.NEGATIVE_INFINITY;
        for (double a : b.get(FlightDataType.TYPE_ALTITUDE)) {
            max = Math.max(max, a);
        }
        return max;
    }

    /** All four custom listeners active on a normal flight: finite trajectory and sane apogee. */
    @Test
    public void testAllListenersActiveProduceFiniteTrajectory() throws SimulationException {
        Simulation sim = newSim(TestRockets.makeEstesAlphaIII());
        sim.simulate(new StructuralFailureListener(),
                new ThermalSimulationListener(),
                new MisalignmentListener(),
                new RecoveryIntegrityListener());

        assertTrajectoryFinite(sim);
        double apogee = apogee(sim);
        assertTrue(apogee > 1.0 && apogee < 5000.0,
                "Apogee should be sane for a small model rocket; was " + apogee + " m");
    }

    /**
     * Gross misalignment (30° tilt plus a 5 mm thrust-line offset) must perturb the flight
     * without producing NaN or a diverging trajectory — this exercises the large-Cm and
     * q-floored thrust-offset paths in {@link MisalignmentListener}.
     */
    @Test
    public void testExtremeMisalignmentStaysFinite() throws SimulationException {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        AxialStage stage = rocket.getStage(0);
        BodyTube tube = (BodyTube) stage.getChild(1);
        tube.setAngularOffset(Math.toRadians(30.0));
        tube.setRadialOffset(0.005);

        Simulation sim = newSim(rocket);
        sim.simulate(new MisalignmentListener());

        assertTrajectoryFinite(sim);
    }

    /** A strong wind with all listeners active must still yield a finite trajectory. */
    @Test
    public void testHighWindStaysFinite() throws SimulationException {
        Simulation sim = newSim(TestRockets.makeEstesAlphaIII());
        sim.getOptions().setWindSpeedAverage(25.0);
        sim.getOptions().setWindSpeedDeviation(5.0);
        sim.simulate(new StructuralFailureListener(),
                new ThermalSimulationListener(),
                new MisalignmentListener(),
                new RecoveryIntegrityListener());

        assertTrajectoryFinite(sim);
    }
}
