package info.openrocket.swing.gui.dialogs;

import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import info.openrocket.core.database.Databases;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.material.Material;
import info.openrocket.core.material.MaterialGroup;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.Unit;
import info.openrocket.core.unit.UnitGroup;

import net.miginfocom.swing.MigLayout;
import info.openrocket.swing.gui.SpinnerEditor;
import info.openrocket.swing.gui.adaptors.DoubleModel;
import info.openrocket.swing.gui.components.StyledLabel;
import info.openrocket.swing.gui.components.UnitSelector;
import info.openrocket.swing.gui.util.GUIUtil;

@SuppressWarnings("serial")
public class CustomMaterialDialog extends JDialog {
	private static final Translator trans = Application.getTranslator();
	
	private final Material originalMaterial;
	private final boolean onlyCopyTypeFromMaterial;
	
	private boolean okClicked = false;
	private JComboBox<Material.Type> typeBox;
	private final JTextField nameField;
	private DoubleModel density;
	private final JSpinner densitySpinner;
	private final UnitSelector densityUnit;
	private DoubleModel shearModulus;
	private final JSpinner shearModulusSpinner;
	private final UnitSelector shearModulusUnit;
	private JComboBox<MaterialGroup> groupBox;
	private JCheckBox addBox;

	// Structural strength properties (backed by DoubleModel for unit conversion)
	private DoubleModel tensileStrengthModel;
	private DoubleModel compressiveStrengthModel;
	private DoubleModel shearStrengthModel;
	private DoubleModel yieldStrengthModel;

	// Thermal properties — temperature uses DoubleModel; conductivity and specific heat use plain spinners
	private DoubleModel meltingPointModel;
	private DoubleModel autoIgnitionTempModel;
	private JSpinner conductivitySpinner;
	private JSpinner specificHeatSpinner;

	public CustomMaterialDialog(Window parent, Material material, boolean saveOption, boolean addToApplicationDatabase,
								String title) {
		this(parent, material, saveOption, addToApplicationDatabase, title, null);
	}

	public CustomMaterialDialog(Window parent, Material material, boolean saveOption, boolean addToApplicationDatabase,
								boolean onlyCopyTypeFromMaterial, String title) {
		this(parent, material, saveOption, addToApplicationDatabase, onlyCopyTypeFromMaterial, title, null);
	}

	public CustomMaterialDialog(Window parent, Material material, boolean saveOption,
			String title) {
		this(parent, material, saveOption, material != null && !material.isDocumentMaterial(), title);
	}

	public CustomMaterialDialog(Window parent, Material material, boolean saveOption, boolean addToApplicationDatabase,
								String title, String note) {
		this(parent, material, saveOption, addToApplicationDatabase, false, title, note);
	}

	public CustomMaterialDialog(Window parent, Material material, boolean saveOption, boolean addToApplicationDatabase,
								boolean onlyCopyTypeFromMaterial, String title, String note) {
		//// Custom material
		super(parent, trans.get("custmatdlg.title.Custommaterial"), Dialog.ModalityType.APPLICATION_MODAL);

		this.originalMaterial = material;
		this.onlyCopyTypeFromMaterial = onlyCopyTypeFromMaterial;

		JPanel panel = new JPanel(new MigLayout("fill, gap rel unrel"));


		// Add title and note
		if (title != null) {
			panel.add(new JLabel("<html><b>" + title + ":"),
					"gapleft para, span, wrap" + (note == null ? " para" : ""));
		}
		if (note != null) {
			panel.add(new StyledLabel(note, -1), "span, wrap para");
		}


		//// Material name
		panel.add(new JLabel(trans.get("custmatdlg.lbl.Materialname")));
		nameField = new JTextField(15);
		if (!onlyCopyTypeFromMaterial && material != null) {
			nameField.setText(material.getName());
		}
		panel.add(nameField, "span, growx, wrap");


		// Material type (if not known)
		panel.add(new JLabel(trans.get("custmatdlg.lbl.Materialtype")));
		if (material == null) {
			// Remove the CUSTOM material option from the dropdown box
			Material.Type[] values = Material.Type.values();
			List<Material.Type> values_list = new LinkedList<>(Arrays.asList(values));
			values_list.remove(Material.Type.CUSTOM);
			values = values_list.toArray(new Material.Type[0]);

			typeBox = new JComboBox<>(values);
			typeBox.setSelectedItem(Material.Type.BULK);
			typeBox.setEditable(false);
			typeBox.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					updateDensityModel();
					updateShearModulusModel();
				}
			});
			panel.add(typeBox, "span, growx, wrap");
		} else {
			panel.add(new JLabel(material.getType().toString()), "span, growx, wrap");
		}


		// Material density:
		panel.add(new JLabel(trans.get("custmatdlg.lbl.Materialdensity")));
		densitySpinner = new JSpinner();
		panel.add(densitySpinner, "w 70lp");
		densityUnit = new UnitSelector((DoubleModel) null);
		panel.add(densityUnit, "w 30lp");
		panel.add(new JPanel(), "growx, wrap");
		updateDensityModel();


		// In-Plane Shear Modulus (only for BULK materials):
		panel.add(new JLabel(trans.get("custmatdlg.lbl.ShearModulus")));
		shearModulusSpinner = new JSpinner();
		panel.add(shearModulusSpinner, "w 70lp");
		shearModulusUnit = new UnitSelector((DoubleModel) null);
		panel.add(shearModulusUnit, "w 30lp");
		panel.add(new JPanel(), "growx, wrap");
		updateShearModulusModel();


		// Material group
		panel.add(new JLabel(trans.get("custmatdlg.lbl.MaterialGroup")));
		groupBox = new JComboBox<>(MaterialGroup.ALL_GROUPS);
		if (!onlyCopyTypeFromMaterial && material != null) {
			groupBox.setSelectedItem(material.getGroup());
		} else {
			groupBox.setSelectedItem(MaterialGroup.CUSTOM);
		}
		panel.add(groupBox, "span, growx, wrap");


		// ---- Structural properties panel ----
		JPanel structPanel = new JPanel(new MigLayout("fillx, gap rel unrel", "[][65lp::][30lp::][]"));
		structPanel.setBorder(BorderFactory.createTitledBorder(trans.get("custmatdlg.border.StructuralProperties")));

		double initTensile = (material != null) ? material.getTensileStrength() : 0.0;
		tensileStrengthModel = new DoubleModel(initTensile, UnitGroup.UNITS_SHEAR_MODULUS, 0);
		addStrengthRow(structPanel, "custmatdlg.lbl.TensileStrength", tensileStrengthModel);

		double initCompressive = (material != null) ? material.getCompressiveStrength() : 0.0;
		compressiveStrengthModel = new DoubleModel(initCompressive, UnitGroup.UNITS_SHEAR_MODULUS, 0);
		addStrengthRow(structPanel, "custmatdlg.lbl.CompressiveStrength", compressiveStrengthModel);

		double initShearStr = (material != null) ? material.getShearStrength() : 0.0;
		shearStrengthModel = new DoubleModel(initShearStr, UnitGroup.UNITS_SHEAR_MODULUS, 0);
		addStrengthRow(structPanel, "custmatdlg.lbl.ShearStrength", shearStrengthModel);

		double initYield = (material != null) ? material.getYieldStrength() : 0.0;
		yieldStrengthModel = new DoubleModel(initYield, UnitGroup.UNITS_SHEAR_MODULUS, 0);
		addStrengthRow(structPanel, "custmatdlg.lbl.YieldStrength", yieldStrengthModel);

		panel.add(structPanel, "span, growx, wrap");

		// ---- Thermal properties panel ----
		JPanel thermalPanel = new JPanel(new MigLayout("fillx, gap rel unrel", "[][65lp::][30lp::][]"));
		thermalPanel.setBorder(BorderFactory.createTitledBorder(trans.get("custmatdlg.border.ThermalProperties")));

		double initMelting = (material != null) ? material.getMeltingPoint() : 0.0;
		meltingPointModel = new DoubleModel(initMelting, UnitGroup.UNITS_TEMPERATURE, 0);
		addTempRow(thermalPanel, "custmatdlg.lbl.MeltingPoint", meltingPointModel);

		double initAutoIgn = (material != null) ? material.getAutoIgnitionTemp() : 0.0;
		autoIgnitionTempModel = new DoubleModel(initAutoIgn, UnitGroup.UNITS_TEMPERATURE, 0);
		addTempRow(thermalPanel, "custmatdlg.lbl.AutoIgnitionTemp", autoIgnitionTempModel);

		double initCond = (material != null) ? material.getThermalConductivity() : 0.0;
		conductivitySpinner = new JSpinner(new SpinnerNumberModel(initCond, 0.0, 10000.0, 0.1));
		conductivitySpinner.setToolTipText(trans.get("custmatdlg.lbl.ttip.ThermalConductivity"));
		thermalPanel.add(new JLabel(trans.get("custmatdlg.lbl.ThermalConductivity")));
		thermalPanel.add(conductivitySpinner, "span 3, growx, wrap");

		double initSH = (material != null) ? material.getSpecificHeat() : 0.0;
		specificHeatSpinner = new JSpinner(new SpinnerNumberModel(initSH, 0.0, 100000.0, 1.0));
		specificHeatSpinner.setToolTipText(trans.get("custmatdlg.lbl.ttip.SpecificHeat"));
		thermalPanel.add(new JLabel(trans.get("custmatdlg.lbl.SpecificHeat")));
		thermalPanel.add(specificHeatSpinner, "span 3, growx, wrap");

		panel.add(thermalPanel, "span, growx, wrap");

		// Save option
		if (saveOption) {
			//// Add material to application database
			addBox = new JCheckBox(trans.get("custmatdlg.checkbox.Addmaterial"));
			addBox.setSelected(addToApplicationDatabase);
			panel.add(addBox, "span, wrap");
		}

		//// OK button
		JButton okButton = new JButton(trans.get("dlg.but.ok"));

		okButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				okClicked = true;
				CustomMaterialDialog.this.setVisible(false);
			}
		});
		panel.add(okButton, "span, split, tag ok");

		////  Cancel
		JButton closeButton = new JButton(trans.get("dlg.but.cancel"));
		closeButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				okClicked = false;
				CustomMaterialDialog.this.setVisible(false);
			}
		});
		panel.add(closeButton, "tag cancel");

		JScrollPane scroll = new JScrollPane(panel,
				JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		this.setContentPane(scroll);
		this.pack();
		java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
		if (getHeight() > screen.height * 0.9) {
			setSize(getWidth(), (int) (screen.height * 0.9));
		}
		this.setLocationByPlatform(true);
		GUIUtil.setDisposableDialogOptions(this, okButton);
	}
	
	public CustomMaterialDialog(Window parent, Material material, boolean saveOption,
			String title, String note) {
		this(parent, material, saveOption, material != null && !material.isDocumentMaterial(), title, note);
	}
	
	
	public boolean getOkClicked() {
		return okClicked;
	}
	
	
	public boolean isAddSelected() {
		return addBox.isSelected();
	}
	
	
	public Material getMaterial() {
		Material.Type type;
		String name;
		double materialDensity;
		double materialShearModulus;
		MaterialGroup group;
		
		if (typeBox != null) {
			type = (Material.Type) typeBox.getSelectedItem();
		} else {
			type = originalMaterial.getType();
		}
		
		name = nameField.getText().trim();
		materialDensity = this.density.getValue();
		materialShearModulus = this.shearModulus != null ? this.shearModulus.getValue() : 0.0;
		group = (MaterialGroup) groupBox.getSelectedItem();
		
		Material mat = Databases.findMaterial(type, name, materialDensity, materialShearModulus, group);
		// Apply the new physical properties
		if (tensileStrengthModel != null) {
			mat.setStrengthProperties(
					tensileStrengthModel.getValue(),
					compressiveStrengthModel.getValue(),
					shearStrengthModel.getValue(),
					yieldStrengthModel.getValue());
		}
		if (meltingPointModel != null) {
			mat.setThermalProperties(
					meltingPointModel.getValue(),
					autoIgnitionTempModel.getValue(),
					((Number) conductivitySpinner.getValue()).doubleValue(),
					((Number) specificHeatSpinner.getValue()).doubleValue());
		}
		return mat;
	}

	private void addStrengthRow(JPanel target, String labelKey, DoubleModel model) {
		JLabel lbl = new JLabel(trans.get(labelKey));
		lbl.setToolTipText(trans.get(labelKey.replace("lbl.", "lbl.ttip.")));
		target.add(lbl);
		JSpinner spin = new JSpinner(model.getSpinnerModel());
		spin.setEditor(new info.openrocket.swing.gui.SpinnerEditor(spin));
		target.add(spin, "growx");
		target.add(new UnitSelector(model), "growx");
		target.add(new javax.swing.JPanel(), "growx, wrap");
	}

	private void addTempRow(JPanel target, String labelKey, DoubleModel model) {
		JLabel lbl = new JLabel(trans.get(labelKey));
		lbl.setToolTipText(trans.get(labelKey.replace("lbl.", "lbl.ttip.")));
		target.add(lbl);
		JSpinner spin = new JSpinner(model.getSpinnerModel());
		spin.setEditor(new info.openrocket.swing.gui.SpinnerEditor(spin));
		target.add(spin, "growx");
		target.add(new UnitSelector(model), "growx");
		target.add(new javax.swing.JPanel(), "growx, wrap");
	}


	private void updateDensityModel() {
		if (originalMaterial != null) {
			if (density == null) {
				double densityValue = onlyCopyTypeFromMaterial ? 0 : originalMaterial.getDensity();
				density = new DoubleModel(densityValue,
						originalMaterial.getType().getUnitGroup(), 0);
				densitySpinner.setModel(density.getSpinnerModel());
				densitySpinner.setEditor(new SpinnerEditor(densitySpinner));
				densityUnit.setModel(density);
			}
		} else {
			Material.Type type = (Material.Type) typeBox.getSelectedItem();
			density = new DoubleModel(0, type.getUnitGroup(), 0);
			densitySpinner.setModel(density.getSpinnerModel());
			densitySpinner.setEditor(new SpinnerEditor(densitySpinner));
			densityUnit.setModel(density);
		}
	}

	private void updateShearModulusModel() {
		// Find GPa unit index for shear modulus (default to GPa for input)
		int gpaUnitIndex = 0;
		try {
			Unit gpaUnit = UnitGroup.UNITS_SHEAR_MODULUS.getUnit("GPa");
			gpaUnitIndex = UnitGroup.UNITS_SHEAR_MODULUS.getUnitIndex(gpaUnit);
		} catch (IllegalArgumentException e) {
			// GPa unit not found, use default index 0 (Pa)
		}
		
		if (originalMaterial != null) {
			if (shearModulus == null) {
				double shearModulusValue = onlyCopyTypeFromMaterial ? 0 : originalMaterial.getInPlaneShearModulus();
				// Use the shear modulus unit group with GPa as the default for input
				shearModulus = new DoubleModel(shearModulusValue,
						UnitGroup.UNITS_SHEAR_MODULUS, gpaUnitIndex);
				shearModulusSpinner.setModel(shearModulus.getSpinnerModel());
				shearModulusSpinner.setEditor(new SpinnerEditor(shearModulusSpinner));
				shearModulusUnit.setModel(shearModulus);
			}
		} else {
			// Use the shear modulus unit group with GPa as the default for input
			shearModulus = new DoubleModel(0, UnitGroup.UNITS_SHEAR_MODULUS, gpaUnitIndex);
			shearModulusSpinner.setModel(shearModulus.getSpinnerModel());
			shearModulusSpinner.setEditor(new SpinnerEditor(shearModulusSpinner));
			shearModulusUnit.setModel(shearModulus);
		}
	}
}
