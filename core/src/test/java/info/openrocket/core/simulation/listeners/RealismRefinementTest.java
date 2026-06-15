package info.openrocket.core.simulation.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.material.Material;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.BondJoint;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.GeodeticComputationStrategy;
import info.openrocket.core.util.TestRockets;

/**
 * Regression tests locking in the physics-realism refinements:
 * <ol>
 *   <li>thermal motor-wall coupling (the mount wall converges to the adjacent gas temperature,
 *       not the core flame temperature);</li>
 *   <li>position-aware structural section load (an aft tube carries more than a forward one);</li>
 *   <li>bond-joint strength is respected (a strong joint does not fail);</li>
 *   <li>geometry-grounded misalignment moment (deviation grows with the offset angle).</li>
 * </ol>
 *
 * @see ThermalSimulationListener
 * @see StructuralFailureListener
 * @see MisalignmentListener
 */
public class RealismRefinementTest extends BaseTestCase {

    // ---- #1 Thermal: exhaust-to-wall coupling -----------------------------------------

    /**
     * With the motor-wall coupling, a motor-mount wall heated for a long burn converges to
     * {@code T_amb + ε·(T_exhaust − T_amb)} — roughly 1060 K for a solid motor — and never
     * approaches the ~2500 K core flame temperature.  Without the coupling it would climb
     * toward the full flame temperature.
     */
    @Test
    public void testExhaustCouplingCapsWallTemperatureBelowFlame() {
        Material mat = Material.newMaterial(Material.Type.BULK, "MountMat", 100.0, false);
        mat.setThermalProperties(0.0, 0.0, 0.2, 100.0); // cp = 100 J/kg·K

        BodyTube tube = new BodyTube(0.1, 0.012, 0.001);
        tube.setMaterial(mat);
        tube.setMotorMount(true);

        ThermalSimulationListener listener = new ThermalSimulationListener();
        final double airTemp = 293.15;
        double T = airTemp;
        for (int i = 0; i < 2000; i++) {
            T = listener.updateTemperature(tube, mat, T, airTemp, 0.05, true);
        }

        double exhaust = ThermalSimulationListener.DEFAULT_EXHAUST_TEMP_K;
        double expected = airTemp + ThermalSimulationListener.EXHAUST_WALL_COUPLING * (exhaust - airTemp);
        assertEquals(expected, T, 5.0,
                "Wall should converge to the coupled gas temperature, not the core flame temp");
        assertTrue(T < 0.6 * exhaust,
                "Coupled wall temperature must stay well below the core flame temperature; was " + T);
    }

    // ---- #2 Structural: position-aware section load ------------------------------------

    /**
     * The axial load carried by a tube's aft cross-section must grow toward the tail (it
     * supports the inertial reaction of everything forward of it), so an aft tube carries
     * strictly more than an identical forward tube — unlike the old flat thrust+drag.
     */
    @Test
    public void testSectionLoadIncreasesTowardTail() {
        Rocket rocket = new Rocket();
        rocket.enableEvents();   // so components auto-position (AFTER stacking) as they are added
        AxialStage stage = new AxialStage();
        rocket.addChild(stage);

        NoseCone nose = new NoseCone(info.openrocket.core.rocketcomponent.Transition.Shape.OGIVE, 0.1, 0.012);
        stage.addChild(nose);
        BodyTube fwd = new BodyTube(0.3, 0.012, 0.001);
        stage.addChild(fwd);
        BodyTube aft = new BodyTube(0.3, 0.012, 0.001);
        stage.addChild(aft);

        // Sanity: the components must actually be stacked nose → fwd → aft for this test to mean
        // anything (a bare rocket without events leaves them all at x = 0).
        double xFwd = fwd.getComponentLocations()[0].getX();
        double xAft = aft.getComponentLocations()[0].getX();
        assertTrue(xAft > xFwd, "Test setup: tubes not stacked (xFwd=" + xFwd + " xAft=" + xAft + ")");

        List<RocketComponent> active = Arrays.asList(nose, fwd, aft);
        double totalMass = nose.getMass() + fwd.getMass() + aft.getMass();
        final double accel = 100.0;
        final double drag = 5.0;

        double noseLoad = StructuralFailureListener.sectionAxialLoad(nose, active, totalMass, accel, drag);
        double fwdLoad = StructuralFailureListener.sectionAxialLoad(fwd, active, totalMass, accel, drag);
        double aftLoad = StructuralFailureListener.sectionAxialLoad(aft, active, totalMass, accel, drag);

        assertEquals(0.0, noseLoad, 1e-9, "Nose cone is not modelled as a load-bearing tube");
        assertTrue(fwdLoad > 0, "Forward tube should carry some load");
        assertTrue(aftLoad > fwdLoad,
                "Aft tube must carry more than the forward tube: aft=" + aftLoad + " fwd=" + fwdLoad);
        assertTrue(aftLoad <= totalMass * accel + Math.abs(drag) + 1e-6,
                "Section load cannot exceed the whole-vehicle load");
    }

    // ---- #3 Bond joint: strength is respected ------------------------------------------

    /** A strong bond joint must not fail on a small model rocket (it should only fail when overloaded). */
    @Test
    public void testStrongBondJointDoesNotFail() throws SimulationException {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        AxialStage stage = rocket.getStage(0);
        BodyTube tube = (BodyTube) stage.getChild(1);

        // 10 MPa shear × 100 cm² × quality 1.0 = 100 kN effective — unbreakable on a model rocket.
        BondJoint strong = new BondJoint(BondJoint.BondType.EPOXY, 0.01, 10e6, 1.0);
        StructuralFailureListener listener = new StructuralFailureListener();
        listener.addBondJoint(tube, strong);

        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        sim.simulate(listener);

        boolean hasBondFailure = sim.getSimulatedData().getBranch(0).getEvents().stream()
                .anyMatch(e -> e.getType() == FlightEvent.Type.BOND_FAILURE);
        assertFalse(hasBondFailure, "A strong bond joint must not fail on a small model rocket");
    }

    // ---- #4 Misalignment: deviation grows with offset ----------------------------------

    /**
     * The geometry-grounded misalignment moment must produce a trajectory deviation that grows
     * with the misalignment angle: a larger tilt trims the rocket further off-axis, drifting more.
     */
    @Test
    public void testMisalignmentDeviationGrowsWithOffset() throws SimulationException {
        double drift0 = lateralDrift(0.0);
        double drift3 = lateralDrift(Math.toRadians(3.0));
        double drift6 = lateralDrift(Math.toRadians(6.0));

        assertTrue(drift3 > drift0,
                "A tilted rocket should deviate more than an aligned one: aligned=" + drift0 + " 3°=" + drift3);
        assertTrue(drift6 > drift3,
                "A larger tilt should deviate more: 3°=" + drift3 + " 6°=" + drift6);
    }

    /** Horizontal distance (m) of touchdown from the pad, in dead-calm flat-earth conditions. */
    private static double lateralDrift(double angularOffset) throws SimulationException {
        Rocket rocket = TestRockets.makeEstesAlphaIII();
        AxialStage stage = rocket.getStage(0);
        BodyTube tube = (BodyTube) stage.getChild(1);
        tube.setAngularOffset(angularOffset);

        Simulation sim = new Simulation(rocket);
        sim.getOptions().setISAAtmosphere(true);
        sim.getOptions().setTimeStep(0.05);
        sim.getOptions().setWindSpeedAverage(0.0);
        sim.getOptions().setWindSpeedDeviation(0.0);
        sim.getOptions().setLaunchRodAngle(0.0);
        sim.getOptions().setGeodeticComputation(GeodeticComputationStrategy.FLAT);
        sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
        sim.simulate(new MisalignmentListener());

        FlightDataBranch b = sim.getSimulatedData().getBranch(0);
        List<Double> px = b.get(FlightDataType.TYPE_POSITION_X);
        List<Double> py = b.get(FlightDataType.TYPE_POSITION_Y);
        int n = px.size();
        return Math.hypot(px.get(n - 1), py.get(n - 1));
    }
}
