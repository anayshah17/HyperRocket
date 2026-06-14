package info.openrocket.core.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.document.StorageOptions;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.util.BaseTestCase;

/**
 * Verifies that user-defined material strength/thermal properties survive both the
 * material-library storable-string format and a full .ork save/load round-trip.
 */
public class MaterialPersistenceTest extends BaseTestCase {

    @TempDir
    Path tempDir;

    // ---- Storable-string (user material library) ----

    @Test
    public void testStorableStringRoundTripWithPhysicalProperties() {
        Material mat = Material.newMaterial(Material.Type.BULK, "MyComposite", 1600.0, 5e9,
                MaterialGroup.COMPOSITES, true);
        mat.setStrengthProperties(250e6, 180e6, 60e6, 200e6);
        mat.setThermalProperties(800.0, 600.0, 0.5, 1000.0);

        Material restored = Material.fromStorableString(mat.toStorableString(), true);

        assertEquals(250e6, restored.getTensileStrength(), 1.0);
        assertEquals(180e6, restored.getCompressiveStrength(), 1.0);
        assertEquals(60e6, restored.getShearStrength(), 1.0);
        assertEquals(200e6, restored.getYieldStrength(), 1.0);
        assertEquals(800.0, restored.getMeltingPoint(), 1e-6);
        assertEquals(600.0, restored.getAutoIgnitionTemp(), 1e-6);
        assertEquals(0.5, restored.getThermalConductivity(), 1e-6);
        assertEquals(1000.0, restored.getSpecificHeat(), 1e-6);
    }

    @Test
    public void testStorableStringWithoutPhysicalStaysCompact() {
        Material mat = Material.newMaterial(Material.Type.BULK, "Plain", 1000.0, 1e9,
                MaterialGroup.PLASTICS, true);
        String s = mat.toStorableString();
        // No physical properties set → original 5-field format (4 separators).
        assertEquals(4, s.chars().filter(ch -> ch == '|').count(),
                "Expected compact 5-field format, got: " + s);
    }

    @Test
    public void testOldFormatStringStillParses() {
        // Legacy 5-field string (no physical properties).
        Material restored = Material.fromStorableString("BULK|Birch|670.0|7.0E8|Woods", false);
        assertNotNull(restored);
        assertEquals(670.0, restored.getDensity(), 1e-6);
        // Unset physical properties fall back to computed defaults (> 0).
        assertTrue(restored.getEffectiveTensileStrength() > 0);
    }

    // ---- Full .ork save/load round-trip ----

    @Test
    public void testMaterialPropertiesSurviveOrkRoundTrip() throws Exception {
        // Minimal rocket (no motor) so the round-trip needs no motor database binding.
        Rocket rocket = new Rocket();
        AxialStage stage = new AxialStage();
        rocket.addChild(stage);
        BodyTube tube = new BodyTube();
        stage.addChild(tube);

        Material custom = Material.newMaterial(Material.Type.BULK, "CustomStrongTube", 900.0, 2e9,
                MaterialGroup.COMPOSITES, true);
        custom.setStrengthProperties(123e6, 99e6, 45e6, 80e6);
        custom.setThermalProperties(750.0, 0.0, 0.3, 1234.0);
        tube.setMaterial(custom);

        OpenRocketDocument doc = OpenRocketDocumentFactory.createDocumentFromRocket(rocket);
        StorageOptions options = new StorageOptions();
        options.setSaveSimulationData(false);

        File orkFile = tempDir.resolve("material_round_trip.ork").toFile();
        new GeneralRocketSaver().save(orkFile, doc, options);

        OpenRocketDocument loaded = new GeneralRocketLoader(orkFile).load();
        BodyTube loadedTube = (BodyTube) loaded.getRocket().getStage(0).getChild(0);
        Material m = loadedTube.getMaterial();

        assertEquals(123e6, m.getTensileStrength(), 1.0, "tensile not persisted");
        assertEquals(99e6, m.getCompressiveStrength(), 1.0, "compressive not persisted");
        assertEquals(45e6, m.getShearStrength(), 1.0, "shear not persisted");
        assertEquals(80e6, m.getYieldStrength(), 1.0, "yield not persisted");
        assertEquals(750.0, m.getMeltingPoint(), 1e-6, "melting not persisted");
        assertEquals(0.3, m.getThermalConductivity(), 1e-6, "conductivity not persisted");
        assertEquals(1234.0, m.getSpecificHeat(), 1e-6, "specific heat not persisted");
    }
}
