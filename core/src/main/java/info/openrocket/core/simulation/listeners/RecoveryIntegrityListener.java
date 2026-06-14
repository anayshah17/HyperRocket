package info.openrocket.core.simulation.listeners;

import info.openrocket.core.logging.Warning;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;

/**
 * Simulation listener that checks recovery device integrity at the moment of deployment.
 * <p>
 * At each {@code RECOVERY_DEVICE_DEPLOYMENT} event the listener:
 * <ol>
 *   <li>Compares the deployment velocity against {@link RecoveryDevice#getMaxDeploymentVelocity()};
 *       fires {@link FlightEvent.Type#PARACHUTE_FAILURE} if the limit is exceeded.</li>
 *   <li>Estimates the opening shock force and compares it against
 *       {@link RecoveryDevice#getShroudLineStrength()} × line count;
 *       fires {@link FlightEvent.Type#PARACHUTE_FAILURE} if the lines would snap.</li>
 * </ol>
 * Fields with value 0 are treated as "not specified" and the corresponding check is skipped.
 */
public class RecoveryIntegrityListener extends AbstractSimulationListener {

    @Override
    public boolean recoveryDeviceDeployment(SimulationStatus status, RecoveryDevice device)
            throws SimulationException {
        FlightDataBranch branch = status.getFlightDataBranch();

        double velocity = safeGet(branch, FlightDataType.TYPE_VELOCITY_TOTAL);
        double airDensity = safeGet(branch, FlightDataType.TYPE_AIR_DENSITY);
        if (airDensity <= 0) {
            airDensity = 1.225; // ISA sea-level fallback
        }

        checkVelocityLimit(status, device, velocity);
        checkShockLoad(status, device, velocity, airDensity);

        return true;
    }

    private void checkVelocityLimit(SimulationStatus status, RecoveryDevice device, double velocity)
            throws SimulationException {
        double limit = device.getMaxDeploymentVelocity();
        if (limit <= 0) {
            return;
        }
        if (velocity > limit) {
            String reason = String.format("%.1f m/s > %.1f m/s limit", velocity, limit);
            status.addWarning(new Warning.ParachuteFailure(device, reason));
            status.addEvent(new FlightEvent(FlightEvent.Type.PARACHUTE_FAILURE,
                    status.getSimulationTime(), device, velocity));
        }
    }

    private void checkShockLoad(SimulationStatus status, RecoveryDevice device,
            double velocity, double airDensity) throws SimulationException {
        double lineStrength = device.getShroudLineStrength();
        if (lineStrength <= 0) {
            return;
        }

        double cd = device.getCD();
        double area = device.getArea();
        double shockFactor = device.getOpeningShockFactor();

        // Opening shock force: F = 0.5 * rho * v^2 * Cd * A * shockFactor
        double shockForce = 0.5 * airDensity * velocity * velocity * cd * area * shockFactor;

        int lineCount = (device instanceof Parachute) ? ((Parachute) device).getLineCount() : 1;
        double totalStrength = lineStrength * lineCount;

        if (shockForce > totalStrength) {
            String reason = String.format("%.0f N shock > %.0f N rated (%d lines × %.0f N)",
                    shockForce, totalStrength, lineCount, lineStrength);
            status.addWarning(new Warning.ParachuteFailure(device, reason));
            status.addEvent(new FlightEvent(FlightEvent.Type.PARACHUTE_FAILURE,
                    status.getSimulationTime(), device, shockForce));
        }
    }

    private static double safeGet(FlightDataBranch branch, FlightDataType type) {
        double v = branch.getLast(type);
        return Double.isNaN(v) ? 0.0 : v;
    }

    @Override
    public RecoveryIntegrityListener clone() {
        return (RecoveryIntegrityListener) super.clone();
    }
}
