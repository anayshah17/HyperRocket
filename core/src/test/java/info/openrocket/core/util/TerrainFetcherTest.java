package info.openrocket.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import info.openrocket.core.util.TerrainFetcher.TerrainData;

/**
 * Sanity tests for the locally-generated desert terrain, in particular the graded
 * launch clearing (which must read as a flat prepared pad, not a pit).
 */
public class TerrainFetcherTest {

    @Test
    public void desertGridIsWellFormed() {
        int n = 129;
        double radius = 800.0;
        TerrainData t = TerrainFetcher.generateDesert(radius, n, 1337L);

        assertEquals(n, t.gridSize);
        assertEquals(2.0 * radius / (n - 1), t.spacingMeters, 1e-9);
        // Launch reference (centre) is altitude 0 in the relative frame.
        int mid = (n - 1) / 2;
        assertEquals(0.0, t.relativeElevation(mid, mid), 1e-9);

        // No NaN/Inf anywhere in the field.
        for (double[] row : t.elevation) {
            for (double v : row) {
                assertTrue(Double.isFinite(v), "non-finite elevation");
            }
        }
    }

    @Test
    public void launchPadIsFlatNotAPit() {
        int n = 129;
        double radius = 800.0;   // spacing ≈ 12.5 m; the pad clearing is ≈ 36 m radius
        TerrainData t = TerrainFetcher.generateDesert(radius, n, 1337L);
        int mid = (n - 1) / 2;
        double centre = t.elevation[mid][mid];

        // The central pad must be flat: samples within the cleared radius equal the
        // centre.  (The old grading punched the centre down to 0 while the dunes
        // rose around it, producing a pit.)
        assertEquals(centre, t.elevation[mid][mid + 1], 1e-9, "pad not flat (+east)");
        assertEquals(centre, t.elevation[mid][mid - 1], 1e-9, "pad not flat (-east)");
        assertEquals(centre, t.elevation[mid + 2][mid], 1e-9, "pad not flat (+north)");

        // And the pad is gently raised, not sunk: it sits above the lowest dune.
        assertTrue(centre > t.minElevation, "launch pad is the lowest point (a pit)");
    }
}
