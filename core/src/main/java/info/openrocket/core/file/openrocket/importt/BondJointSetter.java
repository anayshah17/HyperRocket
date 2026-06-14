package info.openrocket.core.file.openrocket.importt;

import java.util.HashMap;
import java.util.Locale;

import info.openrocket.core.logging.Warning;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.rocketcomponent.BondJoint;
import info.openrocket.core.rocketcomponent.RocketComponent;

/**
 * Sets the {@link BondJoint} of a component from a {@code <bondjoint>} element.
 * <p>
 * The element carries the joint as attributes:
 * {@code type}, {@code area} (m²), {@code shearstrength} (Pa),
 * {@code quality} (0-1) and {@code templimit} (K).  Missing or invalid
 * attributes leave the corresponding default in place.
 */
class BondJointSetter implements Setter {

	@Override
	public void set(RocketComponent c, String value, HashMap<String, String> attributes,
			WarningSet warnings) {

		BondJoint joint = c.getBondJoint();

		String str = attributes.get("type");
		if (str != null) {
			try {
				joint.setType(BondJoint.BondType.valueOf(str.trim().toUpperCase(Locale.ENGLISH)));
			} catch (IllegalArgumentException e) {
				warnings.add(Warning.fromString("Unknown bond joint type '" + str + "', ignoring."));
			}
		}

		joint.setBondArea(parse(attributes.get("area"), joint.getBondArea(), "area", warnings));
		joint.setShearStrength(parse(attributes.get("shearstrength"), joint.getShearStrength(),
				"shearstrength", warnings));
		joint.setQualityFactor(parse(attributes.get("quality"), joint.getQualityFactor(),
				"quality", warnings));
		joint.setTemperatureLimit(parse(attributes.get("templimit"), joint.getTemperatureLimit(),
				"templimit", warnings));

		c.setBondJoint(joint);
	}

	/** Parses a double attribute; returns {@code fallback} when absent, warns when malformed. */
	private static double parse(String str, double fallback, String name, WarningSet warnings) {
		if (str == null) {
			return fallback;
		}
		try {
			return Double.parseDouble(str);
		} catch (NumberFormatException e) {
			warnings.add(Warning.fromString("Illegal bond joint " + name + " value, ignoring."));
			return fallback;
		}
	}
}
