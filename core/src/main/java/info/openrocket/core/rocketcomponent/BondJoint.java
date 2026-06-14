package info.openrocket.core.rocketcomponent;

/**
 * Models the adhesive or mechanical joint between a RocketComponent and its parent.
 * <p>
 * The effective failure load is: {@code shearStrength * qualityFactor * bondArea}.
 * A {@code qualityFactor} of 1.0 represents a perfect textbook joint; real hand-laid
 * epoxy is typically 0.6–0.8.  A value of 0 means the joint is not modeled.
 */
public class BondJoint {

    /** Adhesive or fastener types, ordered roughly by shear strength. */
    public enum BondType {
        TAPE("Tape"),
        WOOD_GLUE("Wood glue"),
        CA("CA (cyanoacrylate)"),
        EPOXY("Epoxy"),
        SCREWS("Screws / bolts"),
        WELD("Weld");

        private final String displayName;

        BondType(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private BondType type;
    /** Bonded surface area in m². */
    private double bondArea;
    /** Rated shear strength of the bond material in Pa. */
    private double shearStrength;
    /** Joint quality factor: 0.0 (no strength) to 1.0 (ideal). */
    private double qualityFactor;
    /** Temperature at which the bond softens or fails, in K.  0 = not specified. */
    private double temperatureLimit;

    /**
     * @param type           adhesive/fastener type
     * @param bondArea       bonded surface area (m²)
     * @param shearStrength  rated shear strength (Pa)
     * @param qualityFactor  joint quality, 0.0–1.0
     * @param tempLimit      temperature limit in K (0 = not specified)
     */
    public BondJoint(BondType type, double bondArea, double shearStrength,
            double qualityFactor, double tempLimit) {
        this.type = type;
        this.bondArea = bondArea;
        this.shearStrength = shearStrength;
        this.qualityFactor = Math.max(0.0, Math.min(1.0, qualityFactor));
        this.temperatureLimit = tempLimit;
    }

    /**
     * Convenience constructor without a temperature limit.
     */
    public BondJoint(BondType type, double bondArea, double shearStrength, double qualityFactor) {
        this(type, bondArea, shearStrength, qualityFactor, 0.0);
    }

    public BondType getType() {
        return type;
    }

    public void setType(BondType type) {
        this.type = type;
    }

    public double getBondArea() {
        return bondArea;
    }

    public void setBondArea(double bondArea) {
        this.bondArea = bondArea;
    }

    public double getShearStrength() {
        return shearStrength;
    }

    public void setShearStrength(double shearStrength) {
        this.shearStrength = shearStrength;
    }

    public double getQualityFactor() {
        return qualityFactor;
    }

    public void setQualityFactor(double qualityFactor) {
        this.qualityFactor = Math.max(0.0, Math.min(1.0, qualityFactor));
    }

    public double getTemperatureLimit() {
        return temperatureLimit;
    }

    public void setTemperatureLimit(double temperatureLimit) {
        this.temperatureLimit = temperatureLimit;
    }

    /**
     * Returns the effective failure load of this joint in Newtons.
     * <p>
     * {@code effectiveStrength = shearStrength * qualityFactor * bondArea}
     *
     * @return effective shear failure load (N), or 0 if the joint is undefined
     */
    public double getEffectiveStrength() {
        return shearStrength * qualityFactor * bondArea;
    }

    @Override
    public String toString() {
        return type + " joint: area=" + bondArea + " m², strength="
                + shearStrength / 1e6 + " MPa, quality=" + qualityFactor;
    }
}
