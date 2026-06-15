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
import info.openrocket.core.util.CoordinateIF;

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

    /**
     * Empirical scale for the roll moment a tilted component sheds.  Unlike the pitch terms
     * (which are derived from geometry, see {@link #postAerodynamicCalculation}), the rolling
     * effect of an off-axis component comes from asymmetric/vortex flow that has no clean
     * closed form in a per-component listener, so this stays a documented approximation.
     */
    private static final double ROLL_COEFF_SCALE = 0.5;

    /** Dynamic pressure (Pa) below which the thrust-offset moment is not injected (avoids the
     *  q-normalised coefficient blowing up at near-zero airspeed just off the pad). */
    private static final double MIN_DYNAMIC_PRESSURE = 1.0;

    /** Components that have already received a misalignment warning this run. */
    private final Set<UUID> warnedComponents = new HashSet<>();

    @Override
    public void startSimulation(SimulationStatus status) throws SimulationException {
        warnedComponents.clear();
    }

    /**
     * Injects the misalignment moment into the total aerodynamic forces so the trajectory
     * actually deviates.  The pitch contributions are derived from real geometry rather than
     * tuned gains:
     * <ul>
     *   <li><b>Angular tilt</b> of a component presents a side force {@code q·A·sin(a)} acting at
     *       its distance from the CG, giving a pitching-moment coefficient
     *       {@code Cm = (A/Aref)·sin(a)·(arm/Lref)} — the dynamic pressure cancels, so it is
     *       purely geometric.</li>
     *   <li><b>Radial (thrust-line) offset</b> of an active motor produces the exact moment
     *       {@code M = thrust·r}, i.e. {@code Cm = thrust·r/(q·Aref·Lref)}.</li>
     * </ul>
     * Returns {@code null} (no change) when the rocket is perfectly aligned.
     */
    @Override
    public AerodynamicForces postAerodynamicCalculation(SimulationStatus status, AerodynamicForces forces)
            throws SimulationException {
        if (!status.isLiftoff() || forces == null) {
            return null;
        }

        // Cheap early-out for a perfectly aligned rocket (the common case): skip the CG,
        // dynamic-pressure and branch work entirely.  This listener is always on and aero is
        // evaluated several times per step, so a nominal flight must pay nothing here.
        boolean hasOffset = false;
        for (RocketComponent comp : status.getConfiguration().getActiveComponents()) {
            if (comp.getAngularOffset() != 0.0 || comp.getRadialOffset() != 0.0) {
                hasOffset = true;
                break;
            }
        }
        if (!hasOffset) {
            return null;
        }

        double aRef = status.getConfiguration().getReferenceArea();
        double lRef = status.getConfiguration().getReferenceLength();
        if (aRef <= 0 || lRef <= 0) {
            return null;
        }

        FlightDataBranch branch = status.getFlightDataBranch();
        double rho = safeGet(branch, FlightDataType.TYPE_AIR_DENSITY);
        if (rho <= 0) {
            rho = 1.225;
        }
        double v = status.getRocketVelocity().length();
        double q = 0.5 * rho * v * v;
        double thrust = safeGet(branch, FlightDataType.TYPE_THRUST_FORCE);
        double cgX = estimateCgX(status);

        double cmAdd = 0.0;
        double rollAdd = 0.0;
        for (RocketComponent comp : status.getConfiguration().getActiveComponents()) {
            double a = comp.getAngularOffset();
            double r = comp.getRadialOffset();
            if (a == 0.0 && r == 0.0) {
                continue;
            }

            if (a != 0.0) {
                double aComp = estimateFrontalArea(comp);
                double arm = axialPosition(comp) - cgX;
                cmAdd += (aComp / aRef) * Math.sin(a) * (arm / lRef);
                rollAdd += ROLL_COEFF_SCALE * (aComp / aRef) * Math.sin(a);
            }

            if (r != 0.0 && thrust > 0.0 && q > MIN_DYNAMIC_PRESSURE) {
                cmAdd += (thrust * r) / (q * aRef * lRef);
            }
        }

        if (cmAdd == 0.0 && rollAdd == 0.0) {
            return null;
        }

        double cm = forces.getCm();
        double croll = forces.getCroll();
        if (!Double.isNaN(cm)) {
            forces.setCm(cm + cmAdd);
        }
        if (!Double.isNaN(croll)) {
            forces.setCroll(croll + rollAdd);
        }
        return forces;
    }

    /** Mass-weighted mean axial position (m) of the active components — a cheap CG estimate. */
    private static double estimateCgX(SimulationStatus status) {
        double mSum = 0.0;
        double mxSum = 0.0;
        for (RocketComponent comp : status.getConfiguration().getActiveComponents()) {
            double m = comp.getMass();
            mSum += m;
            mxSum += m * axialPosition(comp);
        }
        return (mSum > 0) ? mxSum / mSum : 0.0;
    }

    /** Absolute axial position (m, nose = 0) of a component's first instance, or 0 if unknown. */
    private static double axialPosition(RocketComponent comp) {
        CoordinateIF[] locs = comp.getComponentLocations();
        return (locs != null && locs.length > 0) ? locs[0].getX() : 0.0;
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
