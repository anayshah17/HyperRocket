package info.openrocket.core.simulation;

import info.openrocket.core.rocketcomponent.InstanceMap;
import info.openrocket.core.rocketcomponent.RecoveryDevice;

public class BasicLandingStepper extends AbstractEulerStepper {

	/**
	 * Canopy inflation: a real recovery device does not reach full drag the instant it is
	 * released; the canopy fills over a short interval during which its effective drag area
	 * grows from near zero to full.  Modelling this as a smooth ramp (rather than an
	 * instantaneous drag jump) removes the abrupt velocity snap at deployment, which
	 * otherwise makes the descent path kink sharply sideways.
	 * <p>
	 * The fill time scales with canopy size — a larger canopy has farther to travel and more
	 * fabric to fill, so it inflates more slowly.  The classic parachute "fill distance" model
	 * says a canopy fills after travelling a fixed number of canopy diameters; holding the
	 * descent-speed term to a representative constant collapses that to a time that grows
	 * linearly with diameter, {@code t ≈ FILL_TIME_PER_DIAMETER · D}, clamped to a physically
	 * sane range.  (The secondary velocity dependence is dropped deliberately so the inflation
	 * fraction stays monotonic in time across the adaptive descent steps.)
	 */
	/** Inflation seconds per metre of canopy diameter (calibrated so a ~0.4 m chute fills in ~0.4 s). */
	private static final double FILL_TIME_PER_DIAMETER = 1.0;
	private static final double MIN_INFLATION_TIME = 0.15;
	private static final double MAX_INFLATION_TIME = 1.5;

	@Override
	protected double computeCD(SimulationStatus status) {
		// Accumulate CD for all recovery devices, ramping each one up over its inflation time.
		double cd = 0;
		final double now = status.getSimulationTime();
		final InstanceMap imap = status.getConfiguration().getActiveInstances();
		for (RecoveryDevice c : status.getDeployedRecoveryDevices()) {
			cd += imap.count(c) * c.getCD() * c.getArea() * inflationFraction(status, c, now)
					/ status.getConfiguration().getReferenceArea();
		}
		return cd;
	}

	/**
	 * Fraction (0..1) of full drag for a device, given how long ago it deployed.  Uses a
	 * Hermite smoothstep so drag eases in smoothly.  Falls back to fully open (1.0) if no
	 * deployment time was recorded, preserving the original instantaneous behaviour.
	 */
	private static double inflationFraction(SimulationStatus status, RecoveryDevice device, double now) {
		double deployTime = status.getRecoveryDeviceDeploymentTime(device);
		if (Double.isNaN(deployTime)) {
			return 1.0;
		}
		double x = (now - deployTime) / inflationTime(device);
		if (x <= 0) {
			return 0.0;
		}
		if (x >= 1) {
			return 1.0;
		}
		return x * x * (3 - 2 * x);
	}

	/**
	 * Size-dependent canopy fill time (s): larger canopies inflate more slowly.  Derived from
	 * the canopy diameter implied by the device's drag area and clamped to a sane range.
	 */
	static double inflationTime(RecoveryDevice device) {
		double area = device.getArea();
		if (area <= 0) {
			return MIN_INFLATION_TIME;
		}
		double diameter = 2.0 * Math.sqrt(area / Math.PI);
		double t = FILL_TIME_PER_DIAMETER * diameter;
		return Math.max(MIN_INFLATION_TIME, Math.min(MAX_INFLATION_TIME, t));
	}
}
