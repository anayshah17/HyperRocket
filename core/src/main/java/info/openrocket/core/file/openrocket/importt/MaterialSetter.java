package info.openrocket.core.file.openrocket.importt;

import java.util.HashMap;
import java.util.Locale;

import info.openrocket.core.logging.Warning;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.database.Databases;
import info.openrocket.core.material.Material;
import info.openrocket.core.material.MaterialGroup;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.util.Reflection;

////MaterialSetter  -  sets a Material value
class MaterialSetter implements Setter {
	private final Reflection.Method setMethod;
	private final Material.Type type;

	public MaterialSetter(Reflection.Method set, Material.Type type) {
		this.setMethod = set;
		this.type = type;
	}

	@Override
	public void set(RocketComponent c, String name, HashMap<String, String> attributes,
			WarningSet warnings) {

		Material mat;

		// Check name != ""
		name = name.trim();
		if (name.isEmpty()) {
			warnings.add(Warning.fromString("Illegal material specification, ignoring."));
			return;
		}

		// Parse density
		double density;
		String str;
		str = attributes.remove("density");
		if (str == null) {
			warnings.add(Warning.fromString("Illegal material specification, ignoring."));
			return;
		}
		try {
			density = Double.parseDouble(str);
		} catch (NumberFormatException e) {
			warnings.add(Warning.fromString("Illegal material specification, ignoring."));
			return;
		}

		// Parse shear modulus (optional; only use when explicitly provided)
		Double shearModulus = null;
		str = attributes.remove("shearModulus");
		if (str != null) {
			try {
				shearModulus = Double.parseDouble(str);
			} catch (NumberFormatException e) {
				warnings.add(Warning.fromString("Illegal shear modulus value, using 0.0."));
				shearModulus = 0.0;
			}
		}

		// Parse thickness
		// double thickness = 0;
		// str = attributes.remove("thickness");
		// try {
		// if (str != null)
		// thickness = Double.parseDouble(str);
		// } catch (NumberFormatException e){
		// warnings.add(Warning.fromString("Illegal material specification,
		// ignoring."));
		// return;
		// }

		// Check type if specified
		str = attributes.remove("type");
		if (str != null && !type.name().toLowerCase(Locale.ENGLISH).equals(str)) {
			warnings.add(Warning.fromString("Illegal material type specified, ignoring."));
			return;
		}

		// Check for material group
		str = attributes.remove("group");
		MaterialGroup group = null;
		if (str != null) {
			try {
				group = MaterialGroup.loadFromDatabaseStringWithBackwardCompatibility(str, type, name, density);
			} catch (IllegalArgumentException e) {
				warnings.add(Warning.fromString("Illegal material group specified, ignoring."));
			}
		}

		if (shearModulus == null) {
			mat = Databases.findMaterial(type, name, density, group);
		} else {
			mat = Databases.findMaterial(type, name, density, shearModulus, group);
		}

		// Parse optional structural / thermal properties.  When present, apply them to
		// a user-defined copy so we never mutate a shared database material instance.
		double tensile = optionalDouble(attributes, "tensileStrength", warnings);
		double compressive = optionalDouble(attributes, "compressiveStrength", warnings);
		double shearStrength = optionalDouble(attributes, "shearStrength", warnings);
		double yield = optionalDouble(attributes, "yieldStrength", warnings);
		double melting = optionalDouble(attributes, "meltingPoint", warnings);
		double autoIgnition = optionalDouble(attributes, "autoIgnitionTemp", warnings);
		double conductivity = optionalDouble(attributes, "thermalConductivity", warnings);
		double specificHeat = optionalDouble(attributes, "specificHeat", warnings);

		boolean hasPhysical = tensile > 0 || compressive > 0 || shearStrength > 0 || yield > 0
				|| melting > 0 || autoIgnition > 0 || conductivity > 0 || specificHeat > 0;
		if (hasPhysical) {
			Material copy = Material.newMaterial(type, mat.getName(), mat.getDensity(),
					mat.getInPlaneShearModulus(), mat.getGroup(), true);
			copy.setStrengthProperties(tensile, compressive, shearStrength, yield);
			copy.setThermalProperties(melting, autoIgnition, conductivity, specificHeat);
			mat = copy;
		}

		setMethod.invoke(c, mat);
	}

	/** Parses an optional positive double attribute, removing it; returns 0 if absent/invalid. */
	private static double optionalDouble(HashMap<String, String> attributes, String key,
			WarningSet warnings) {
		String str = attributes.remove(key);
		if (str == null) {
			return 0.0;
		}
		try {
			return Double.parseDouble(str);
		} catch (NumberFormatException e) {
			warnings.add(Warning.fromString("Illegal " + key + " value, ignoring."));
			return 0.0;
		}
	}
}
