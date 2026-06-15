package info.openrocket.core.simulation;

import info.openrocket.core.rocketcomponent.InstanceMap;
import info.openrocket.core.rocketcomponent.RecoveryDevice;

public class BasicLandingStepper extends AbstractEulerStepper {

	/**
	 * Canopy inflation time (s).  A real recovery device does not reach full drag the
	 * instant it is released: the canopy fills over a short interval, during which its
	 * effective drag area grows from near zero to full.  Modelling this as a smooth
	 * ramp (rather than an instantaneous drag jump) removes the abrupt velocity snap at
	 * deployment, which otherwise makes the descent path kink sharply sideways.
	 */
	private static final double INFLATION_TIME = 0.4;

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
		double x = (now - deployTime) / INFLATION_TIME;
		if (x <= 0) {
			return 0.0;
		}
		if (x >= 1) {
			return 1.0;
		}
		return x * x * (3 - 2 * x);
	}
}
