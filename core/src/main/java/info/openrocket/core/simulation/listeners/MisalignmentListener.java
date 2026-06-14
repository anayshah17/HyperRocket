package info.openrocket.core.simulation.listeners;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.ExternalComponent;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;

/**
 * Simulation listener that models component misalignment and applies its effect to the flight.
 * <p>
 * Any {@link RocketComponent} with a non-zero {@code angularOffset} (axis tilt, rad) or
 * {@code radialOffset} (centerline displacement, m) introduces an asymmetric aerodynamic
 * load.  This listener does two things:
 * <ol>
 *   <li><b>Perturbs the trajectory</b> in {@link #postAerodynamicCalculation}: a tilted or
 *       off-centre component adds a steady pitching moment ({@code Cm}) and a roll moment
 *       ({@code Croll}) to the total aerodynamic forces, so the rocket trims off-axis and
 *       visibly veers / corkscrews — the larger the offset, the larger the deviation.</li>
 *   <li><b>Warns the user</b> in {@link #postStep}: the first time the estimated side force
 *       exceeds {@value #SIDE_FORCE_WARNING_THRESHOLD_N} N during powered flight a
 *       {@link Warning.Other} is raised naming the offending component.</li>
 * </ol>
 */
public class MisalignmentListener extends AbstractSimulationListener {

    private static final double SIDE_FORCE_WARNING_THRESHOLD_N = 0.1;

    /** Pitch-moment coefficient added per radian of summed angular offset. */
    private static final double PITCH_GAIN_PER_RAD = 3.0;
    /** Roll-moment coefficient added per radian of summed angular offset. */
    private static final double ROLL_GAIN_PER_RAD = 0.6;
    /** Pitch-moment coefficient added per metre of summed radial offset (thrust-line offset). */
    private static final double RADIAL_PITCH_GAIN_PER_M = 8.0;

    /** Components that have already received a misalignment warning this run. */
    private final Set<UUID> warnedComponents = new HashSet<>();

    @Override
    public void startSimulation(SimulationStatus status) throws SimulationException {
        warnedComponents.clear();
    }

    /**
     * Injects the misalignment moment into the total aerodynamic forces so the
     * trajectory actually deviates.  Returns {@code null} (no change) when the
     * rocket is perfectly aligned.
     */
    @Override
    public AerodynamicForces postAerodynamicCalculation(SimulationStatus status, AerodynamicForces forces)
            throws SimulationException {
        if (!status.isLiftoff() || forces == null) {
            return null;
        }

        double pitchAdd = 0.0;
        double rollAdd = 0.0;
        for (RocketComponent comp : status.getConfiguration().getActiveComponents()) {
            double a = comp.getAngularOffset();
            double r = comp.getRadialOffset();
            if (a == 0.0 && r == 0.0) {
                continue;
            }
            pitchAdd += a * PITCH_GAIN_PER_RAD + r * RADIAL_PITCH_GAIN_PER_M;
            rollAdd += a * ROLL_GAIN_PER_RAD;
        }

        if (pitchAdd == 0.0 && rollAdd == 0.0) {
            return null;
        }

        double cm = forces.getCm();
        double croll = forces.getCroll();
        if (!Double.isNaN(cm)) {
            forces.setCm(cm + pitchAdd);
        }
        if (!Double.isNaN(croll)) {
            forces.setCroll(croll + rollAdd);
        }
        return forces;
    }

    @Override
    public void postStep(SimulationStatus status) throws SimulationException {
        if (!status.isLiftoff()) {
            return;
        }

        FlightDataBranch branch = status.getFlightDataBranch();
        double velocity = safeGet(branch, FlightDataType.TYPE_VELOCITY_TOTAL);
        double airDensity = safeGet(branch, FlightDataType.TYPE_AIR_DENSITY);
        if (airDensity <= 0) {
            airDensity = 1.225;
        }

        double dynamicPressure = 0.5 * airDensity * velocity * velocity;
        if (dynamicPressure < 1.0) {
            return; // negligible dynamic pressure, skip
        }

        for (RocketComponent comp : status.getConfiguration().getActiveComponents()) {
            if (warnedComponents.contains(comp.getID())) {
                continue;
            }

            double angularOffset = comp.getAngularOffset();
            double radialOffset = comp.getRadialOffset();

            if (angularOffset == 0.0 && radialOffset == 0.0) {
                continue;
            }

            double frontalArea = estimateFrontalArea(comp);

            // Estimated transverse force from angular tilt
            double sideForce = dynamicPressure * frontalArea * Math.abs(Math.sin(angularOffset));

            // Radial offset adds a moment arm to drag — estimate as drag force × offset
            double dragForce = safeGet(branch, FlightDataType.TYPE_DRAG_FORCE);
            double offsetTorque = Math.abs(dragForce) * radialOffset;

            if (sideForce >= SIDE_FORCE_WARNING_THRESHOLD_N || offsetTorque >= SIDE_FORCE_WARNING_THRESHOLD_N) {
                warnedComponents.add(comp.getID());
                String msg = buildMessage(comp, angularOffset, radialOffset, sideForce, offsetTorque);
                status.addWarning(new Warning.Other(msg));
            }
        }
    }

    private static double estimateFrontalArea(RocketComponent comp) {
        if (comp instanceof BodyTube) {
            double r = ((BodyTube) comp).getOuterRadius();
            return Math.PI * r * r;
        }
        if (comp instanceof ExternalComponent) {
            return 1e-4; // 1 cm² fallback for other external components
        }
        return 1e-4;
    }

    private static String buildMessage(RocketComponent comp, double angularOffset,
            double radialOffset, double sideForce, double offsetTorque) {
        StringBuilder sb = new StringBuilder("Component misalignment: ");
        sb.append(comp.getName());
        if (angularOffset != 0.0) {
            sb.append(String.format(" angular=%.3f°", Math.toDegrees(angularOffset)));
        }
        if (radialOffset != 0.0) {
            sb.append(String.format(" radial=%.1f mm", radialOffset * 1000.0));
        }
        if (sideForce >= SIDE_FORCE_WARNING_THRESHOLD_N) {
            sb.append(String.format(" (est. side force %.1f N)", sideForce));
        }
        if (offsetTorque >= SIDE_FORCE_WARNING_THRESHOLD_N) {
            sb.append(String.format(" (est. torque %.1f N·m)", offsetTorque));
        }
        return sb.toString();
    }

    private static double safeGet(FlightDataBranch branch, FlightDataType type) {
        double v = branch.getLast(type);
        return Double.isNaN(v) ? 0.0 : v;
    }

    @Override
    public MisalignmentListener clone() {
        MisalignmentListener copy = (MisalignmentListener) super.clone();
        copy.warnedComponents.clear();
        copy.warnedComponents.addAll(this.warnedComponents);
        return copy;
    }
}
