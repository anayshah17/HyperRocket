package info.openrocket.core.material;

import info.openrocket.core.l10n.Translator;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.Unit;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.util.Groupable;
import info.openrocket.core.util.MathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A class for different material types.  Each material has a name and density.
 * The interpretation of the density depends on the material type.  For
 * {@link Type#BULK} it is kg/m^3, for {@link Type#SURFACE} km/m^2.
 * <p>
 * Objects of this type are immutable.
 * 
 * @author Sampo Niskanen <sampo.niskanen@iki.fi>
 */

public abstract class Material implements Comparable<Material>, Groupable<MaterialGroup> {
	private static final Translator trans = Application.getTranslator();
	private static final Logger log = LoggerFactory.getLogger(Material.class);

	public enum Type {
		BULK("Databases.materials.types.Bulk", UnitGroup.UNITS_DENSITY_BULK),
		SURFACE("Databases.materials.types.Surface", UnitGroup.UNITS_DENSITY_SURFACE),
		LINE("Databases.materials.types.Line", UnitGroup.UNITS_DENSITY_LINE),
		CUSTOM("Databases.materials.types.Custom", UnitGroup.UNITS_DENSITY_BULK);
		
		private final String name;
		private final UnitGroup units;
		
		private Type(String nameKey, UnitGroup units) {
			this.name = trans.get(nameKey);
			this.units = units;
		}
		
		public UnitGroup getUnitGroup() {
			return units;
		}
		
		@Override
		public String toString() {
			return name;
		}
	}
	
	
	/////  Definitions of different material types  /////
	
	public static class Line extends Material {
		Line(String name, double density, double inPlaneShearModulus, MaterialGroup group, boolean userDefined, boolean documentMaterial) {
			super(name, density, inPlaneShearModulus, group, userDefined, documentMaterial);
		}

		Line(String name, double density, double inPlaneShearModulus, MaterialGroup group, boolean userDefined) {
			super(name, density, inPlaneShearModulus, group, userDefined, false);
		}

		Line(String name, double density, MaterialGroup group, boolean userDefined, boolean documentMaterial) {
			super(name, density, 0.0, group, userDefined, documentMaterial);
		}

		Line(String name, double density, MaterialGroup group, boolean userDefined) {
			super(name, density, 0.0, group, userDefined);
		}

		Line(String name, double density, boolean userDefined) {
			super(name, density, 0.0, userDefined);
		}
		
		@Override
		public Type getType() {
			return Type.LINE;
		}
	}
	
	public static class Surface extends Material {
		Surface(String name, double density, double inPlaneShearModulus, MaterialGroup group, boolean userDefined, boolean documentMaterial) {
			super(name, density, inPlaneShearModulus, group, userDefined, documentMaterial);
		}

		Surface(String name, double density, double inPlaneShearModulus, MaterialGroup group, boolean userDefined) {
			super(name, density, inPlaneShearModulus, group, userDefined, false);
		}

		Surface(String name, double density, MaterialGroup group, boolean userDefined, boolean documentMaterial) {
			super(name, density, 0.0, group, userDefined, documentMaterial);
		}

		Surface(String name, double density, MaterialGroup group, boolean userDefined) {
			super(name, density, 0.0, group, userDefined);
		}

		Surface(String name, double density, boolean userDefined) {
			super(name, density, 0.0, userDefined);
		}
		
		@Override
		public Type getType() {
			return Type.SURFACE;
		}
		
		@Override
		public String toStorableString() {
			return super.toStorableString();
		}
	}
	
	public static class Bulk extends Material {
		Bulk(String name, double density, double inPlaneShearModulus, MaterialGroup group, boolean userDefined, boolean documentMaterial) {
			super(name, density, inPlaneShearModulus, group, userDefined, documentMaterial);
		}

		Bulk(String name, double density, double inPlaneShearModulus, MaterialGroup group, boolean userDefined) {
			super(name, density, inPlaneShearModulus, group, userDefined, false);
		}

		Bulk(String name, double density, MaterialGroup group, boolean userDefined, boolean documentMaterial) {
			super(name, density, 0.0, group, userDefined, documentMaterial);
		}

		Bulk(String name, double density, MaterialGroup group, boolean userDefined) {
			super(name, density, 0.0, group, userDefined);
		}

		Bulk(String name, double density, boolean userDefined) {
			super(name, density, 0.0, userDefined);
		}
		
		@Override
		public Type getType() {
			return Type.BULK;
		}
	}
	

	public static class Custom extends Material {
		Custom(String name, double density, double inPlaneShearModulus, MaterialGroup group, boolean userDefined, boolean documentMaterial) {
			super(name, density, inPlaneShearModulus, group, userDefined, documentMaterial);
		}

		Custom(String name, double density, double inPlaneShearModulus, MaterialGroup group, boolean userDefined) {
			super(name, density, inPlaneShearModulus, group, userDefined, false);
		}

		Custom(String name, double density, MaterialGroup group, boolean userDefined, boolean documentMaterial) {
			super(name, density, 0.0, group, userDefined, documentMaterial);
		}

		Custom(String name, double density, MaterialGroup group, boolean userDefined) {
			super(name, density, 0.0, group, userDefined);
		}

		Custom(String name, double density, boolean userDefined) {
			super(name, density, 0.0, userDefined);
		}
		
		@Override
		public Type getType() {
			return Type.CUSTOM;
		}
	}
	
	
	
	private String name;
	private double density;
	private double inPlaneShearModulus;
	private boolean userDefined;
	private boolean documentMaterial;
	private MaterialGroup group;

	// Structural strength properties (Pa). 0.0 means not specified.
	private double tensileStrength = 0.0;
	private double compressiveStrength = 0.0;
	private double shearStrength = 0.0;
	private double yieldStrength = 0.0;

	// Thermal properties. 0.0 means not specified.
	private double meltingPoint = 0.0;       // K
	private double autoIgnitionTemp = 0.0;   // K
	private double thermalConductivity = 0.0; // W/(m·K)
	private double specificHeat = 0.0;        // J/(kg·K)
	
	
	/**
	 * Constructor for materials.
	 * 
	 * @param name ignored when defining system materials.
	 * @param density: the density of the material.
	 * @param inPlaneShearModulus: the in-plane shear modulus G of the material (in Pa).
	 * @param group the material group.
	 * @param userDefined true if this is a user defined material, false if it is a system material.
	 * @param documentMaterial true if this material is stored in the document preferences.
	 */
	private Material(String name, double density, double inPlaneShearModulus, MaterialGroup group, boolean userDefined, boolean documentMaterial) {
		this.name = name;
		this.density = density;
		this.inPlaneShearModulus = inPlaneShearModulus;
		this.userDefined = userDefined;
		this.documentMaterial = documentMaterial;
		this.group = getEquivalentGroup(group, userDefined);
	}

	private Material(String name, double density, double inPlaneShearModulus, MaterialGroup group, boolean userDefined) {
		this(name, density, inPlaneShearModulus, group, userDefined, false);
	}

	private Material(String name, double density, double inPlaneShearModulus, boolean userDefined) {
		this(name, density, inPlaneShearModulus, null, userDefined);
	}

	private Material(String name, double density, MaterialGroup group, boolean userDefined, boolean documentMaterial) {
		this(name, density, 0.0, group, userDefined, documentMaterial);
	}

	private Material(String name, double density, MaterialGroup group, boolean userDefined) {
		this(name, density, 0.0, group, userDefined, false);
	}

	private Material(String name, double density, boolean userDefined) {
		this(name, density, 0.0, null, userDefined);
	}
	
	public double getDensity() {
		return density;
	}

	/**
	 * Get the in-plane shear modulus G of the material.
	 * 
	 * @return the in-plane shear modulus in Pascals (Pa)
	 */
	public double getInPlaneShearModulus() {
		return inPlaneShearModulus;
	}
	
	public String getName() {
		return name;
	}
	
	public String getName(Unit u) {
		return name + " (" + u.toStringUnit(density) + ")";
	}
	
	public boolean isUserDefined() {
		return userDefined;
	}

	public boolean isDocumentMaterial() {
		return documentMaterial;
	}

	public void setDocumentMaterial(boolean documentMaterial) {
		this.documentMaterial = documentMaterial;
	}

	public abstract Type getType();

	public MaterialGroup getGroup() {
		return group;
	}

	/**
	 * Some materials have a null group. This method returns the equivalent group, i.e. CUSTOM for user-defined materials,
	 * and OTHER for materials with a null group.
	 *
	 * @param group: the group of the material
	 * @param userDefined: whether the material is user-defined or not
	 *
	 * @return the equivalent group
	 */
	private static MaterialGroup getEquivalentGroup(MaterialGroup group, boolean userDefined) {
		if (group != null) {
			return group;
		}
		if (userDefined) {
			return MaterialGroup.CUSTOM;
		}
		return MaterialGroup.OTHER;
	}

	// ---- Structural strength getters ----

	/** @return ultimate tensile strength in Pa, or 0.0 if not specified */
	public double getTensileStrength() { return tensileStrength; }

	/** @return compressive strength in Pa, or 0.0 if not specified */
	public double getCompressiveStrength() { return compressiveStrength; }

	/** @return shear strength in Pa, or 0.0 if not specified */
	public double getShearStrength() { return shearStrength; }

	/** @return yield strength in Pa, or 0.0 if not specified */
	public double getYieldStrength() { return yieldStrength; }

	// ---- Thermal property getters ----

	/** @return melting point in K, or 0.0 if not specified */
	public double getMeltingPoint() { return meltingPoint; }

	/** @return auto-ignition temperature in K, or 0.0 if not specified */
	public double getAutoIgnitionTemp() { return autoIgnitionTemp; }

	/** @return thermal conductivity in W/(m·K), or 0.0 if not specified */
	public double getThermalConductivity() { return thermalConductivity; }

	/** @return specific heat capacity in J/(kg·K), or 0.0 if not specified */
	public double getSpecificHeat() { return specificHeat; }

	/**
	 * Set structural strength properties. All values in Pa.
	 */
	public void setStrengthProperties(double tensile, double compressive, double shear, double yield) {
		this.tensileStrength = tensile;
		this.compressiveStrength = compressive;
		this.shearStrength = shear;
		this.yieldStrength = yield;
	}

	/**
	 * Set thermal properties.
	 * @param melting melting point in K
	 * @param autoIgnition auto-ignition temperature in K
	 * @param conductivity thermal conductivity in W/(m·K)
	 * @param specificHeat specific heat capacity in J/(kg·K)
	 */
	public void setThermalProperties(double melting, double autoIgnition, double conductivity, double specificHeat) {
		this.meltingPoint = melting;
		this.autoIgnitionTemp = autoIgnition;
		this.thermalConductivity = conductivity;
		this.specificHeat = specificHeat;
	}

	// ---- Effective property getters -----------------------------------------
	// These return the explicitly-set value when available, otherwise a
	// representative default derived from the material group/density/name
	// (see MaterialPhysicalDefaults).  The failure-simulation listeners use
	// these so that stock materials, which carry no strength/thermal data,
	// still produce meaningful results.

	/** @return tensile strength (Pa); a group/density estimate if unset. */
	public double getEffectiveTensileStrength() {
		return tensileStrength > 0 ? tensileStrength : MaterialPhysicalDefaults.tensileStrength(this);
	}

	/** @return compressive strength (Pa); a group/density estimate if unset. */
	public double getEffectiveCompressiveStrength() {
		return compressiveStrength > 0 ? compressiveStrength : MaterialPhysicalDefaults.compressiveStrength(this);
	}

	/** @return shear strength (Pa); a group/density estimate if unset. */
	public double getEffectiveShearStrength() {
		return shearStrength > 0 ? shearStrength : MaterialPhysicalDefaults.shearStrength(this);
	}

	/** @return yield strength (Pa); a group/density estimate if unset. */
	public double getEffectiveYieldStrength() {
		return yieldStrength > 0 ? yieldStrength : MaterialPhysicalDefaults.yieldStrength(this);
	}

	/** @return melting point (K), or 0 for materials that char rather than melt. */
	public double getEffectiveMeltingPoint() {
		return meltingPoint > 0 ? meltingPoint : MaterialPhysicalDefaults.meltingPoint(this);
	}

	/** @return auto-ignition temperature (K), or 0 if melting governs. */
	public double getEffectiveAutoIgnitionTemp() {
		return autoIgnitionTemp > 0 ? autoIgnitionTemp : MaterialPhysicalDefaults.autoIgnitionTemp(this);
	}

	/** @return specific heat (J/kg·K); always positive. */
	public double getEffectiveSpecificHeat() {
		return specificHeat > 0 ? specificHeat : MaterialPhysicalDefaults.specificHeat(this);
	}

	/** @return thermal conductivity (W/m·K); always positive. */
	public double getEffectiveThermalConductivity() {
		return thermalConductivity > 0 ? thermalConductivity : MaterialPhysicalDefaults.thermalConductivity(this);
	}

	/**
	 * Populate any unset strength/thermal properties with the representative
	 * defaults for this material's group/density/name.  Already-specified
	 * values are left untouched.  Used to enrich the built-in material
	 * database so the values are visible in the material editor.
	 */
	public void applyPhysicalDefaults() {
		if (tensileStrength <= 0)      tensileStrength = MaterialPhysicalDefaults.tensileStrength(this);
		if (compressiveStrength <= 0)  compressiveStrength = MaterialPhysicalDefaults.compressiveStrength(this);
		if (shearStrength <= 0)        shearStrength = MaterialPhysicalDefaults.shearStrength(this);
		if (yieldStrength <= 0)        yieldStrength = MaterialPhysicalDefaults.yieldStrength(this);
		if (meltingPoint <= 0)         meltingPoint = MaterialPhysicalDefaults.meltingPoint(this);
		if (autoIgnitionTemp <= 0)     autoIgnitionTemp = MaterialPhysicalDefaults.autoIgnitionTemp(this);
		if (specificHeat <= 0)         specificHeat = MaterialPhysicalDefaults.specificHeat(this);
		if (thermalConductivity <= 0)  thermalConductivity = MaterialPhysicalDefaults.thermalConductivity(this);
	}

	public int getGroupPriority() {
		if (group == null) {
			return Integer.MAX_VALUE;
		}
		return group.getPriority();
	}
	
	@Override
	public String toString() {
		return this.getName(this.getType().getUnitGroup().getDefaultUnit());
	}
	
	
	/**
	 * Compares this object to another object.  Material objects are equal if and only if
	 * their types, names, densities, and in-plane shear moduli are identical.
	 */
	@Override
	public boolean equals(Object o) {
		if (o == null)
			return false;
		if (this.getClass() != o.getClass())
			return false;
		Material m = (Material) o;
		return ((m.name.equals(this.name)) && MathUtil.equals(m.density, this.density)
				&& MathUtil.equals(m.inPlaneShearModulus, this.inPlaneShearModulus))
				&& MathUtil.equals(m.tensileStrength, this.tensileStrength)
				&& MathUtil.equals(m.compressiveStrength, this.compressiveStrength)
				&& MathUtil.equals(m.shearStrength, this.shearStrength)
				&& MathUtil.equals(m.yieldStrength, this.yieldStrength)
				&& MathUtil.equals(m.meltingPoint, this.meltingPoint)
				&& MathUtil.equals(m.autoIgnitionTemp, this.autoIgnitionTemp)
				&& MathUtil.equals(m.thermalConductivity, this.thermalConductivity)
				&& MathUtil.equals(m.specificHeat, this.specificHeat)
				&& groupsEqual(m);
	}

	private boolean groupsEqual(Material m) {
		if (group == null) {
			return m.group == null;
		}
		return group.equals(m.group);
	}
	
	
	/**
	 * A hashCode() method giving a hash code compatible with the equals() method.
	 */
	@Override
	public int hashCode() {
		return name.hashCode() + (int) (density * 1000) + (int) (inPlaneShearModulus * 1e-9);
	}
	
	
	/**
	 * Order the materials according to their name, secondarily according to density.
	 */
	@Override
	public int compareTo(Material o) {
		int c = this.name.compareTo(o.name);
		if (c != 0) {
			return c;
		} else {
			return (int) ((this.density - o.density) * 1000);
		}
	}
	
	
	/**
	 * Return a new material.  The name is used as-is, without any translation.
	 * 
	 * @param type			the material type
	 * @param name			the material name
	 * @param density		the material density
	 * @param inPlaneShearModulus	the in-plane shear modulus G (in Pa)
	 * @param group			the material group
	 * @param userDefined	whether the material is user-defined or not
	 * @param documentMaterial	whether the material is stored in the document preferences
	 * @return				the new material
	 */
	public static Material newMaterial(Type type, String name, double density, double inPlaneShearModulus, MaterialGroup group, boolean userDefined,
									   boolean documentMaterial) {
		return switch (type) {
			case LINE -> new Line(name, density, inPlaneShearModulus, group, userDefined, documentMaterial);
			case SURFACE -> new Surface(name, density, inPlaneShearModulus, group, userDefined, documentMaterial);
			case BULK -> new Bulk(name, density, inPlaneShearModulus, group, userDefined, documentMaterial);
			case CUSTOM -> new Custom(name, density, inPlaneShearModulus, group, userDefined, documentMaterial);
		};
	}

	public static Material newMaterial(Type type, String name, double density, MaterialGroup group, boolean userDefined,
									   boolean documentMaterial) {
		return newMaterial(type, name, density, 0.0, group, userDefined, documentMaterial);
	}

	public static Material newMaterial(Type type, String name, double density, MaterialGroup group, boolean userDefined) {
		return newMaterial(type, name, density, 0.0, group, userDefined, false);
	}

	public static Material newMaterial(Type type, String name, double density, double inPlaneShearModulus, MaterialGroup group, boolean userDefined) {
		return newMaterial(type, name, density, inPlaneShearModulus, group, userDefined, false);
	}

	public static Material newMaterial(Type type, String name, double density, double inPlaneShearModulus, boolean userDefined,
									   boolean documentMaterial) {
		return newMaterial(type, name, density, inPlaneShearModulus, null, userDefined, documentMaterial);
	}

	public static Material newMaterial(Type type, String name, double density, boolean userDefined,
									   boolean documentMaterial) {
		return newMaterial(type, name, density, 0.0, null, userDefined, documentMaterial);
	}

	public static Material newMaterial(Type type, String name, double density, boolean userDefined) {
		return newMaterial(type, name, density, 0.0, null, userDefined, false);
	}

	public void loadFrom(Material m) {
		if (m == null)
			throw new IllegalArgumentException("Material is null");
		if (this.getClass() != m.getClass())
			throw new IllegalArgumentException("Material type mismatch");
		name = m.name;
		density = m.density;
		inPlaneShearModulus = m.inPlaneShearModulus;
		group = m.group;
		userDefined = m.userDefined;
		documentMaterial = m.documentMaterial;
		tensileStrength = m.tensileStrength;
		compressiveStrength = m.compressiveStrength;
		shearStrength = m.shearStrength;
		yieldStrength = m.yieldStrength;
		meltingPoint = m.meltingPoint;
		autoIgnitionTemp = m.autoIgnitionTemp;
		thermalConductivity = m.thermalConductivity;
		specificHeat = m.specificHeat;
	}
	
	public String toStorableString() {
		String base = getType().name() + "|" + name.replace('|', ' ') + '|' + density + '|'
				+ inPlaneShearModulus + '|' + group.getDatabaseString();
		// Append structural/thermal properties only when any is set, so materials
		// without them keep the original 5-field format (fully backward compatible).
		boolean hasPhysical = tensileStrength > 0 || compressiveStrength > 0 || shearStrength > 0
				|| yieldStrength > 0 || meltingPoint > 0 || autoIgnitionTemp > 0
				|| thermalConductivity > 0 || specificHeat > 0;
		if (hasPhysical) {
			base += "|" + tensileStrength + "|" + compressiveStrength + "|" + shearStrength
					+ "|" + yieldStrength + "|" + meltingPoint + "|" + autoIgnitionTemp
					+ "|" + thermalConductivity + "|" + specificHeat;
		}
		return base;
	}

	
	/**
	 * Return a material defined by the provided string.
	 * 
	 * @param str			the material storage string, formatted as "{type}|{name}|{density}|{inPlaneShearModulus}|{group}".
	 * 						For backward compatibility, the format "{type}|{name}|{density}|{group}" is also supported.
	 * @param userDefined	whether the created material is user-defined.
	 * @return				a new <code>Material</code> object.
	 * @throws IllegalArgumentException		if <code>str</code> is invalid or null.
	 */
	public static Material fromStorableString(String str, boolean userDefined) {
		if (str == null)
			throw new IllegalArgumentException("Material string is null");
		
		String[] split = str.split("\\|", -1);
		if (split.length < 3)
			throw new IllegalArgumentException("Illegal material string: " + str);
		
		Type type;
		String name;
		double density;
		double inPlaneShearModulus = 0.0;
		MaterialGroup group = null;
		
		try {
			type = Type.valueOf(split[0]);
		} catch (Exception e) {
			throw new IllegalArgumentException("Illegal material string: " + str, e);
		}
		
		name = split[1];
		
		try {
			density = Double.parseDouble(split[2]);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Illegal material string: " + str, e);
		}

		// Handle backward compatibility: old format has 4 fields (type|name|density|group),
		// 5-field format adds inPlaneShearModulus, and the 13-field format appends the eight
		// structural/thermal properties (type|name|density|shear|group|tensile|compressive|
		// shear|yield|melting|autoIgnition|conductivity|specificHeat).
		boolean newFormat = false;
		if (split.length >= 4) {
			try {
				// Try to parse the 4th field as a double (shear modulus)
				inPlaneShearModulus = Double.parseDouble(split[3]);
				newFormat = true;
				// If successful and there's a 5th field, it's the group
				if (split.length >= 5) {
					try {
						group = MaterialGroup.loadFromDatabaseStringWithBackwardCompatibility(split[4], type, name, density);
					} catch (IllegalArgumentException e) {
						log.debug(e.toString());
					}
				}
			} catch (NumberFormatException e) {
				// 4th field is not a number, so it must be the group (old format)
				try {
					group = MaterialGroup.loadFromDatabaseStringWithBackwardCompatibility(split[3], type, name, density);
				} catch (IllegalArgumentException ex) {
					log.debug(ex.toString());
				}
			}
		}

		Material result = switch (type) {
			case BULK -> new Bulk(name, density, inPlaneShearModulus, group, userDefined);
			case SURFACE -> new Surface(name, density, inPlaneShearModulus, group, userDefined);
			case LINE -> new Line(name, density, inPlaneShearModulus, group, userDefined);
			default -> throw new IllegalArgumentException("Illegal material string: " + str);
		};

		// Restore appended structural/thermal properties when present.
		if (newFormat && split.length >= 13) {
			try {
				result.setStrengthProperties(
						Double.parseDouble(split[5]), Double.parseDouble(split[6]),
						Double.parseDouble(split[7]), Double.parseDouble(split[8]));
				result.setThermalProperties(
						Double.parseDouble(split[9]), Double.parseDouble(split[10]),
						Double.parseDouble(split[11]), Double.parseDouble(split[12]));
			} catch (NumberFormatException e) {
				log.debug("Ignoring malformed material physical properties: " + str);
			}
		}

		return result;
	}
	
}
