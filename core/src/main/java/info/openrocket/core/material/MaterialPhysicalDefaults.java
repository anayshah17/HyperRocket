package info.openrocket.core.material;

import java.util.Locale;

/**
 * Provides reasonable default structural-strength and thermal properties for a
 * {@link Material} when those properties have not been explicitly specified.
 * <p>
 * Stock OpenRocket materials only carry density and shear modulus, so the
 * structural- and thermal-failure simulation listeners would otherwise have no
 * data to work with and would silently do nothing.  These heuristics fill that
 * gap based on the material's {@link MaterialGroup}, density and name, using
 * representative engineering values (orders of magnitude, not lab-grade).
 * <p>
 * All strength values are in Pa; temperatures in K; conductivity in W/(m·K);
 * specific heat in J/(kg·K).  Methods return {@code 0.0} only when a property is
 * genuinely not applicable (e.g. a melting point for wood, which chars instead).
 */
public final class MaterialPhysicalDefaults {

    private MaterialPhysicalDefaults() { }

    // ---- Strength ----------------------------------------------------------

    /** Ultimate tensile strength (Pa) estimated from group/density/name. */
    public static double tensileStrength(Material mat) {
        MaterialGroup g = mat.getGroup();
        double rho = mat.getDensity();
        String name = lower(mat.getName());

        if (g == MaterialGroup.METALS) {
            if (name.contains("titanium")) return 900e6;
            if (name.contains("steel"))    return 500e6;
            if (name.contains("brass"))    return 350e6;
            if (name.contains("alumin"))   return 310e6;
            return 250e6;
        }
        if (g == MaterialGroup.COMPOSITES) {
            if (name.contains("carbon"))   return 600e6;
            if (name.contains("fiberglass") || name.contains("glass")) return 250e6;
            if (name.contains("phenolic") || name.contains("tube"))    return 60e6;
            return 200e6;
        }
        if (g == MaterialGroup.WOODS)  return Math.max(2e6, rho * 45_000.0);   // balsa~8, plywood~28 MPa
        if (g == MaterialGroup.PAPER)  return Math.max(5e6, rho * 30_000.0);   // cardboard~20 MPa
        if (g == MaterialGroup.FOAMS)  return Math.max(0.2e6, rho * 12_000.0); // EPS~0.24 MPa
        if (g == MaterialGroup.PLASTICS) return Math.max(20e6, rho * 32_000.0);// PLA~44 MPa
        if (g == MaterialGroup.FIBERS || g == MaterialGroup.FABRICS
                || g == MaterialGroup.NYLONS || g == MaterialGroup.KEVLARS) return 100e6;
        if (g == MaterialGroup.ELASTICS) return 15e6;
        return Math.max(10e6, rho * 20_000.0); // OTHER / unknown
    }

    public static double compressiveStrength(Material mat) {
        return tensileStrength(mat) * factor(mat, 1.0, 1.0, 0.6, 0.7);
    }

    public static double shearStrength(Material mat) {
        return tensileStrength(mat) * factor(mat, 0.6, 0.5, 0.15, 0.3);
    }

    public static double yieldStrength(Material mat) {
        return tensileStrength(mat) * factor(mat, 0.85, 0.7, 0.8, 0.8);
    }

    /**
     * Group-dependent multiplier helper.
     *
     * @param metal    factor for metals
     * @param plastic  factor for plastics
     * @param brittle  factor for woods/paper/foams (brittle, weak in this mode)
     * @param other    factor for everything else
     */
    private static double factor(Material mat, double metal, double plastic, double brittle, double other) {
        MaterialGroup g = mat.getGroup();
        if (g == MaterialGroup.METALS) return metal;
        if (g == MaterialGroup.PLASTICS || g == MaterialGroup.ELASTICS) return plastic;
        if (g == MaterialGroup.WOODS || g == MaterialGroup.PAPER || g == MaterialGroup.FOAMS) return brittle;
        return other;
    }

    // ---- Thermal -----------------------------------------------------------

    /** Melting point (K), or 0 if the material chars/ignites rather than melts. */
    public static double meltingPoint(Material mat) {
        MaterialGroup g = mat.getGroup();
        String name = lower(mat.getName());
        if (g == MaterialGroup.METALS) {
            if (name.contains("titanium")) return 1941;
            if (name.contains("steel"))    return 1700;
            if (name.contains("brass"))    return 1190;
            if (name.contains("alumin"))   return 933;
            return 1000;
        }
        if (g == MaterialGroup.PLASTICS) {
            if (name.contains("pla"))  return 440;
            if (name.contains("abs"))  return 378;
            if (name.contains("petg")) return 420;
            return 430;
        }
        if (g == MaterialGroup.FOAMS)  return 370;
        if (g == MaterialGroup.NYLONS || g == MaterialGroup.FIBERS) return 490;
        return 0.0; // woods, paper, composites: no clean melting point
    }

    /** Auto-ignition / charring temperature (K), or 0 if melting governs. */
    public static double autoIgnitionTemp(Material mat) {
        MaterialGroup g = mat.getGroup();
        if (g == MaterialGroup.WOODS)      return 570;
        if (g == MaterialGroup.PAPER)      return 506;
        if (g == MaterialGroup.COMPOSITES) return 600;
        if (g == MaterialGroup.FABRICS || g == MaterialGroup.KEVLARS) return 700;
        return 0.0;
    }

    /** Specific heat capacity (J/kg·K). Always positive. */
    public static double specificHeat(Material mat) {
        MaterialGroup g = mat.getGroup();
        String name = lower(mat.getName());
        if (g == MaterialGroup.METALS) {
            if (name.contains("alumin")) return 900;
            if (name.contains("steel"))  return 490;
            if (name.contains("brass"))  return 380;
            if (name.contains("titanium")) return 520;
            return 500;
        }
        if (g == MaterialGroup.WOODS)      return 1700;
        if (g == MaterialGroup.PAPER)      return 1400;
        if (g == MaterialGroup.PLASTICS || g == MaterialGroup.ELASTICS) return 1800;
        if (g == MaterialGroup.FOAMS)      return 1300;
        if (g == MaterialGroup.COMPOSITES) return 1000;
        if (g == MaterialGroup.FIBERS || g == MaterialGroup.FABRICS
                || g == MaterialGroup.NYLONS || g == MaterialGroup.KEVLARS) return 1500;
        return 1200;
    }

    /** Thermal conductivity (W/m·K). Always positive. */
    public static double thermalConductivity(Material mat) {
        MaterialGroup g = mat.getGroup();
        if (g == MaterialGroup.METALS)     return 120;
        if (g == MaterialGroup.WOODS)      return 0.15;
        if (g == MaterialGroup.PAPER)      return 0.05;
        if (g == MaterialGroup.PLASTICS || g == MaterialGroup.ELASTICS) return 0.2;
        if (g == MaterialGroup.FOAMS)      return 0.035;
        if (g == MaterialGroup.COMPOSITES) return 0.5;
        return 0.3;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
