package info.openrocket.core.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.util.BaseTestCase;
import info.openrocket.core.util.GeodeticComputationStrategy;
import info.openrocket.core.util.TestRockets;

/**
 * Regression tests verifying descent realism, the calm-air drift fix and the
 * parachute-failure veto against known analytic expectations.
 *
 * @see PinkNoiseWindModel  (turbulence fade-out near calm)
 * @see BasicLandingStepper  (canopy inflation transient)
 * @see RecoveryIntegrityListener  (failed device provides no drag)
 */
public class RecoveryRealismTest extends BaseTestCase {

	private static Parachute getParachute(Rocket rocket) {
		AxialStage stage = rocket.getStage(0);
		return (Parachute) stage.getChild(1).getChild(3);
	}

	private Simulation newSim(Rocket rocket) {
		Simulation sim = new Simulation(rocket);
		sim.getOptions().setISAAtmosphere(true);
		sim.getOptions().setTimeStep(0.05);
		sim.getOptions().setLaunchRodAngle(0.0);
		sim.setFlightConfigurationId(TestRockets.TEST_FCID_0);
		return sim;
	}

	/** Horizontal distance (m) of the final recorded sample from the launch point. */
	private static double landingDrift(Simulation sim) {
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		List<Double> px = b.get(FlightDataType.TYPE_POSITION_X);
		List<Double> py = b.get(FlightDataType.TYPE_POSITION_Y);
		int n = px.size();
		return Math.hypot(px.get(n - 1), py.get(n - 1));
	}

	/**
	 * In true dead-calm air (FLAT earth so there is no Coriolis either) a symmetric
	 * rocket launched vertically must come down essentially where it went up.
	 */
	@Test
	public void testCalmAirDescendsVertically() throws SimulationException {
		Simulation sim = newSim(TestRockets.makeEstesAlphaIII());
		sim.getOptions().setWindSpeedAverage(0.0);
		sim.getOptions().setWindSpeedDeviation(0.0);
		sim.getOptions().setGeodeticComputation(GeodeticComputationStrategy.FLAT);
		sim.simulate();

		assertEquals(0.0, landingDrift(sim), 0.05,
				"Dead-calm vertical launch should land within a few cm of the pad");
	}

	/**
	 * Turbulence left on at zero mean wind must NOT create a large one-directional
	 * drift.  Previously the single-axis pink-noise low-frequency bias behaved like a
	 * phantom steady breeze and slid the rocket several metres during the slow descent
	 * (measured ~5 m); the calm fade-out keeps it small.
	 */
	@Test
	public void testTurbulenceAtZeroMeanDoesNotDrift() throws SimulationException {
		Simulation sim = newSim(TestRockets.makeEstesAlphaIII());
		sim.getOptions().setWindSpeedAverage(0.0);
		sim.getOptions().setWindSpeedDeviation(0.5); // turbulence on, mean calm
		sim.simulate();

		assertTrue(landingDrift(sim) < 0.5,
				"Zero-mean turbulence should not produce a large drift, but drifted "
						+ landingDrift(sim) + " m");
	}

	/**
	 * A working parachute brings the rocket down slowly; a parachute whose maximum
	 * deployment velocity (set by the user) is exceeded fails, provides no drag, and lets
	 * the rocket come in ballistically -- with a PARACHUTE_FAILURE warning so the user
	 * knows why.  A stock chute that sets no limit is never affected.
	 */
	@Test
	public void testExceededLimitFailsDestructively() throws SimulationException {
		// Working chute baseline (no limit set): should touch down gently.
		Simulation working = newSim(TestRockets.makeEstesAlphaIII());
		working.getOptions().setWindSpeedAverage(0.0);
		working.simulate();
		double workingLandingSpeed = landingSpeed(working);
		assertTrue(workingLandingSpeed < 10.0,
				"Working parachute should land slowly, but landed at " + workingLandingSpeed + " m/s");

		// Identical rocket but the chute is rated for deployment only up to 1 m/s.
		Rocket failRocket = TestRockets.makeEstesAlphaIII();
		getParachute(failRocket).setMaxDeploymentVelocity(1.0);
		Simulation failing = newSim(failRocket);
		failing.getOptions().setWindSpeedAverage(0.0);
		failing.simulate();

		List<FlightEvent> events = failing.getSimulatedData().getBranch(0).getEvents();
		boolean hasFailure = events.stream()
				.anyMatch(e -> e.getType() == FlightEvent.Type.PARACHUTE_FAILURE);
		assertTrue(hasFailure, "Expected PARACHUTE_FAILURE warning event; got: " + events);

		// With no drag from the failed chute the rocket comes in far faster.
		double failingLandingSpeed = landingSpeed(failing);
		assertTrue(failingLandingSpeed > 2.0 * workingLandingSpeed,
				"A failed parachute should land much faster: working " + workingLandingSpeed
						+ " m/s, failed " + failingLandingSpeed + " m/s");
	}

	/** Total speed (m/s) at the final recorded sample (touchdown). */
	private static double landingSpeed(Simulation sim) {
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		List<Double> v = b.get(FlightDataType.TYPE_VELOCITY_TOTAL);
		return v.get(v.size() - 1);
	}

	/**
	 * The canopy fill time scales with size — a larger canopy takes longer to inflate — and is
	 * clamped to a physically sane range.
	 *
	 * @see BasicLandingStepper#inflationTime
	 */
	@Test
	public void testInflationTimeScalesWithCanopySize() {
		Parachute small = new Parachute();
		small.setDiameter(0.2);   // 0.2 m canopy
		Parachute large = new Parachute();
		large.setDiameter(1.5);   // 1.5 m canopy

		double tSmall = BasicLandingStepper.inflationTime(small);
		double tLarge = BasicLandingStepper.inflationTime(large);

		assertTrue(tLarge > tSmall,
				"A larger canopy must take longer to inflate: small=" + tSmall + " large=" + tLarge);
		assertTrue(tSmall >= 0.15 - 1e-9 && tLarge <= 1.5 + 1e-9,
				"Inflation time must stay within the documented [0.15, 1.5] s range");
	}
}
