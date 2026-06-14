package info.openrocket.core.simulation.listeners;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import info.openrocket.core.logging.Warning;
import info.openrocket.core.material.Material;
import info.openrocket.core.rocketcomponent.BondJoint;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.ExternalComponent;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;

/**
 * Simulation listener that checks structural integrity at every time step.
 * <p>
 * For each active {@link ExternalComponent} whose material has a non-zero tensile or
 * compressive strength, the listener estimates the axial stress from the net thrust
 * and drag forces and fires a {@link FlightEvent.Type#STRUCTURAL_FAILURE} event plus
 * a {@link Warning.StructuralFailure} warning the first time a limit is exceeded.
 * <p>
 * Bond joints registered via {@link #addBondJoint(RocketComponent, BondJoint)} are
 * checked against the estimated lateral shear load on each component.  A
 * {@link FlightEvent.Type#BOND_FAILURE} followed by
 * {@link FlightEvent.Type#COMPONENT_SEPARATION} is fired on first failure.
 * <p>
 * Each component fires at most one failure event for the lifetime of the simulation.
 */
public class StructuralFailureListener extends AbstractSimulationListener {

    /** Components that have already failed — prevents duplicate events. */
    private final Set<UUID> failedComponents = new HashSet<>();

    /** Optional bond joints, keyed by component UUID. */
    private final Map<UUID, BondJoint> bondJoints = new HashMap<>();

    /**
     * Register a bond joint for a specific component.  The listener will check this
     * joint's shear strength on every step after liftoff.
     *
     * @param comp  the component whose bond to its parent is being modeled
     * @param joint the bond joint parameters
     */
    public void addBondJoint(RocketComponent comp, BondJoint joint) {
        bondJoints.put(comp.getID(), joint);
    }

    @Override
    public void startSimulation(SimulationStatus status) throws SimulationException {
        failedComponents.clear();
    }

    @Override
    public void postStep(SimulationStatus status) throws SimulationException {
        if (!status.isLiftoff()) {
            return;
        }

        FlightDataBranch branch = status.getFlightDataBranch();
        double thrust = safeGet(branch, FlightDataType.TYPE_THRUST_FORCE);
        double drag = safeGet(branch, FlightDataType.TYPE_DRAG_FORCE);
        // Total acceleration magnitude — covers both axial (boost) and lateral loading
        double totalAccel = safeGet(branch, FlightDataType.TYPE_ACCELERATION_TOTAL);

        // Net axial load carried by the airframe (conservative: sum of magnitudes)
        double axialLoad = Math.abs(thrust) + Math.abs(drag);

        for (RocketComponent comp : status.getConfiguration().getActiveComponents()) {
            if (!(comp instanceof ExternalComponent)) {
                continue;
            }
            ExternalComponent ext = (ExternalComponent) comp;
            Material mat = ext.getMaterial();
            if (mat == null) {
                continue;
            }

            checkAxialStress(status, comp, mat, axialLoad);
            checkBondJoint(status, branch, comp, ext, totalAccel);
        }
    }

    private void checkAxialStress(SimulationStatus status, RocketComponent comp,
            Material mat, double axialLoad) throws SimulationException {
        if (failedComponents.contains(comp.getID())) {
            return;
        }

        // Strength values fall back to group/density estimates when a material
        // carries no explicit data, so stock rockets are still evaluated.
        double tensile = mat.getEffectiveTensileStrength();
        double compressive = mat.getEffectiveCompressiveStrength();
        if (tensile <= 0 && compressive <= 0) {
            return;
        }
        double limit = lowestPositive(tensile, compressive);

        double wallArea = computeWallArea(comp);
        if (wallArea <= 0) {
            return;
        }

        double stress = axialLoad / wallArea;
        if (stress > limit) {
            failedComponents.add(comp.getID());
            status.addWarning(new Warning.StructuralFailure(comp, stress));
            status.addEvent(new FlightEvent(FlightEvent.Type.STRUCTURAL_FAILURE,
                    status.getSimulationTime(), comp, stress));
            // A failed airframe can no longer fly stably — make it tumble.
            status.addEvent(new FlightEvent(FlightEvent.Type.TUMBLE,
                    status.getSimulationTime(), comp, null));
        }
    }

    private void checkBondJoint(SimulationStatus status, FlightDataBranch branch,
            RocketComponent comp, ExternalComponent ext, double totalAcceleration)
            throws SimulationException {
        if (failedComponents.contains(comp.getID())) {
            return;
        }

        BondJoint joint = bondJoints.get(comp.getID());
        if (joint == null) {
            return;
        }

        // Shear load estimate: F = m × |a_total| — covers axial (boost) and lateral loading.
        // A joint with zero effective strength fails under any non-trivial load.
        double mass = ext.getComponentMass();
        double shearForce = mass * totalAcceleration;

        double strength = joint.getEffectiveStrength();
        // Zero-strength joint always fails when any load is applied
        if (strength > 0 && shearForce <= strength) {
            return;
        }
        if (shearForce <= 0) {
            return; // No load yet
        }

        failedComponents.add(comp.getID());
        status.addWarning(new Warning.BondFailure(comp, shearForce));
        status.addEvent(new FlightEvent(FlightEvent.Type.BOND_FAILURE,
                status.getSimulationTime(), comp, shearForce));
        status.addEvent(new FlightEvent(FlightEvent.Type.COMPONENT_SEPARATION,
                status.getSimulationTime(), comp, null));
        // A separated joint destroys stability — the rocket tumbles.
        status.addEvent(new FlightEvent(FlightEvent.Type.TUMBLE,
                status.getSimulationTime(), comp, null));
    }

    /**
     * Returns the annular cross-sectional wall area for structural tubes, or 0 for
     * component types we don't model.
     */
    private static double computeWallArea(RocketComponent comp) {
        if (comp instanceof BodyTube) {
            BodyTube tube = (BodyTube) comp;
            double ro = tube.getOuterRadius();
            double ri = tube.getInnerRadius();
            return Math.PI * (ro * ro - ri * ri);
        }
        return 0.0;
    }

    /** Returns the smallest positive value among the arguments, or 0 if none is positive. */
    private static double lowestPositive(double a, double b) {
        if (a > 0 && b > 0) {
            return Math.min(a, b);
        }
        return Math.max(a, b); // one of them is 0 or negative
    }

    /** Reads a value from the branch, returning 0 for NaN. */
    private static double safeGet(FlightDataBranch branch, FlightDataType type) {
        double v = branch.getLast(type);
        return Double.isNaN(v) ? 0.0 : v;
    }

    @Override
    public StructuralFailureListener clone() {
        StructuralFailureListener copy = (StructuralFailureListener) super.clone();
        copy.failedComponents.clear();
        copy.failedComponents.addAll(this.failedComponents);
        copy.bondJoints.putAll(this.bondJoints);
        return copy;
    }
}
