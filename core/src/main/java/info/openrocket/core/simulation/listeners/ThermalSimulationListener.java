package info.openrocket.core.simulation.listeners;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import info.openrocket.core.logging.Warning;
import info.openrocket.core.material.Material;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.ExternalComponent;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;

/**
 * Simulation listener that models heat transfer from motor exhaust to nearby
 * structural components and checks for thermal failure.
 * <p>
 * A lumped-mass heat transfer model is used:
 * <pre>
 *   Q  = h × A_contact × (T_exhaust − T_component)     [W]
 *   dT = Q × dt / (m × cp)                              [K per step]
 * </pre>
 * where {@code h} is the convective coefficient ({@value #CONVECTIVE_COEFF} W/m²·K),
 * {@code A_contact} is the inner surface area of the BodyTube exposed to exhaust,
 * {@code m} is the component mass, and {@code cp} is the specific heat from
 * {@link Material#getSpecificHeat()}.
 * <p>
 * Cooling toward ambient air temperature applies when no motor is burning.
 * <p>
 * A {@link FlightEvent.Type#THERMAL_FAILURE} event plus a {@link Warning.ThermalFailure}
 * warning is fired once per component the first time its temperature exceeds the lower
 * of its material's melting point or auto-ignition temperature.
 * <p>
 * Components whose material has neither a specific heat nor any thermal limit defined
 * are skipped.
 * <p>
 * The motor exhaust temperature defaults to {@value #DEFAULT_EXHAUST_TEMP_K} K.
 * Override with {@link #setExhaustTempK(double)} to match a specific motor.
 */
public class ThermalSimulationListener extends AbstractSimulationListener {

    /** Default motor exhaust temperature (K) — representative of solid propellant motors. */
    public static final double DEFAULT_EXHAUST_TEMP_K = 2500.0;

    /**
     * Simplified convective heat-transfer coefficient (W/m²·K).
     * Real rocket motor values are 200–2000 W/m²·K depending on gas velocity;
     * this conservatively lower value suits a coarse lumped-mass model.
     */
    public static final double CONVECTIVE_COEFF = 150.0;

    /** Assumed ambient start temperature (K) when atmospheric data is not yet available. */
    private static final double DEFAULT_AMBIENT_K = 293.15;

    /**
     * Fraction of the core-exhaust-to-ambient temperature rise that actually reaches a
     * motor-mount wall.  Hobby and most amateur motors are self-contained: the propellant
     * burns inside the motor casing and liner, so the mount tube wall sits behind that casing
     * and a gas boundary layer and never approaches the ~2500 K core flame temperature.  This
     * effectiveness drives the wall toward a believable adjacent-gas temperature
     * ({@code T_ambient + ε·(T_exhaust − T_ambient)} ≈ 1000 K for a metal mount) rather than
     * the full core flame temperature, which would otherwise over-predict thermal failure.
     */
    public static final double EXHAUST_WALL_COUPLING = 0.35;

    private double exhaustTempK = DEFAULT_EXHAUST_TEMP_K;

    /** Temperature of each component in K, updated every step. */
    private final Map<UUID, Double> temperatures = new HashMap<>();

    /** Components that have already fired a thermal failure event. */
    private final Set<UUID> failedComponents = new HashSet<>();

    // ---- Configuration ----

    public double getExhaustTempK() {
        return exhaustTempK;
    }

    /**
     * Override the default exhaust temperature.
     *
     * @param exhaustTempK exhaust gas temperature in K
     */
    public void setExhaustTempK(double exhaustTempK) {
        this.exhaustTempK = exhaustTempK;
    }

    /** Returns an unmodifiable snapshot of current component temperatures (K), keyed by UUID. */
    public Map<UUID, Double> getComponentTemperatures() {
        return Collections.unmodifiableMap(temperatures);
    }

    // ---- Listener lifecycle ----

    @Override
    public void startSimulation(SimulationStatus status) throws SimulationException {
        temperatures.clear();
        failedComponents.clear();
    }

    @Override
    public void postStep(SimulationStatus status) throws SimulationException {
        FlightDataBranch branch = status.getFlightDataBranch();
        double thrust = safeGet(branch, FlightDataType.TYPE_THRUST_FORCE);
        double dt = safeGet(branch, FlightDataType.TYPE_TIME_STEP);
        double airTemp = safeGet(branch, FlightDataType.TYPE_AIR_TEMPERATURE);

        if (airTemp <= 0) {
            airTemp = DEFAULT_AMBIENT_K;
        }
        if (dt <= 0) {
            return; // no time has passed, nothing to integrate
        }

        boolean motorBurning = thrust > 0;

        for (RocketComponent comp : status.getConfiguration().getActiveComponents()) {
            if (!(comp instanceof ExternalComponent)) {
                continue;
            }
            ExternalComponent ext = (ExternalComponent) comp;
            Material mat = ext.getMaterial();
            if (mat == null) {
                continue;
            }

            double T = temperatures.getOrDefault(comp.getID(), airTemp);
            T = updateTemperature(ext, mat, T, airTemp, dt, motorBurning);
            temperatures.put(comp.getID(), T);

            checkThermalLimits(status, comp, mat, T);
        }
    }

    // ---- Internal helpers ----

    /**
     * Advances the lumped-mass temperature of {@code comp} by one time step.
     *
     * @param ext         the component
     * @param mat         its material
     * @param currentTemp current component temperature (K)
     * @param airTemp     current ambient air temperature (K)
     * @param dt          time step (s)
     * @param motorOn     true while a motor is burning
     * @return updated temperature (K)
     */
    double updateTemperature(ExternalComponent ext, Material mat,
            double currentTemp, double airTemp, double dt, boolean motorOn) {
        double mass = ext.getComponentMass();
        if (mass <= 0) {
            return currentTemp;
        }

        double contactArea = computeExhaustContactArea(ext);
        // While the motor burns, the wall is driven toward the gas adjacent to it, not the
        // full core flame temperature (see EXHAUST_WALL_COUPLING).  Off the motor it relaxes
        // toward ambient air.
        double sourceTemp = motorOn
                ? airTemp + EXHAUST_WALL_COUPLING * (exhaustTempK - airTemp)
                : airTemp;

        // Closed-form (analytic) solution of  dT/dt = k·(T_src − T)  over the step,
        // with k = h·A / (m·cp).  Unlike explicit forward-Euler this is unconditionally
        // stable and never overshoots the source temperature, so a small specific heat
        // or a large time step cannot make the temperature diverge or oscillate.
        double k = CONVECTIVE_COEFF * contactArea / (mass * mat.getEffectiveSpecificHeat());
        return sourceTemp + (currentTemp - sourceTemp) * Math.exp(-k * dt);
    }

    /**
     * Fires a {@link FlightEvent.Type#THERMAL_FAILURE} event if the component's
     * temperature has exceeded its thermal limit for the first time.
     */
    private void checkThermalLimits(SimulationStatus status, RocketComponent comp,
            Material mat, double tempK) throws SimulationException {
        if (failedComponents.contains(comp.getID())) {
            return;
        }

        double limit = thermalLimit(mat);
        if (limit <= 0 || tempK <= limit) {
            return;
        }

        failedComponents.add(comp.getID());
        status.addWarning(new Warning.ThermalFailure(comp, tempK, limit));
        status.addEvent(new FlightEvent(FlightEvent.Type.THERMAL_FAILURE,
                status.getSimulationTime(), comp, tempK));
    }

    /**
     * Returns the lowest positive thermal failure threshold for {@code mat}, or 0 if
     * neither melting point nor auto-ignition temperature is specified.
     */
    private static double thermalLimit(Material mat) {
        double melting = mat.getEffectiveMeltingPoint();
        double ignition = mat.getEffectiveAutoIgnitionTemp();

        if (melting > 0 && ignition > 0) {
            return Math.min(melting, ignition);
        }
        return Math.max(melting, ignition); // returns the one that is > 0, or 0
    }

    /**
     * Returns the inner surface area of a BodyTube exposed to motor exhaust (m²).
     * For other component types returns a small non-zero default so they still receive
     * some heat, but far less than a tube surrounding the motor.
     */
    private static double computeExhaustContactArea(ExternalComponent comp) {
        if (comp instanceof BodyTube) {
            BodyTube tube = (BodyTube) comp;
            double innerArea = 2.0 * Math.PI * tube.getInnerRadius() * tube.getLength();
            // Only the tube actually housing a motor is bathed in exhaust gas.
            // Other airframe tubes see only weak, indirect convection.
            if (tube.isMotorMount()) {
                return innerArea;
            }
            return innerArea * 0.02;
        }
        // For nose cones, fins, etc.: minimal indirect exposure
        return 1e-4; // 1 cm²
    }

    /** Reads a branch value, returning 0 for NaN. */
    private static double safeGet(FlightDataBranch branch, FlightDataType type) {
        double v = branch.getLast(type);
        return Double.isNaN(v) ? 0.0 : v;
    }

    @Override
    public ThermalSimulationListener clone() {
        ThermalSimulationListener copy = (ThermalSimulationListener) super.clone();
        copy.temperatures.putAll(this.temperatures);
        copy.failedComponents.addAll(this.failedComponents);
        return copy;
    }
}
