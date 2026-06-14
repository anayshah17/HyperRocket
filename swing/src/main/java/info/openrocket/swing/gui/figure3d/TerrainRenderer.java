package info.openrocket.swing.gui.figure3d;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;

import info.openrocket.core.util.TerrainFetcher.TerrainData;

/**
 * Renders a {@link TerrainData} elevation grid as a lit, texture-mapped desert
 * mesh in the 3D flight-replay GL frame.
 * <p>
 * Realism beyond flat vertex colours comes from three baked-in effects, computed
 * once per vertex and captured in the caller's display list:
 * <ul>
 *   <li><b>Tiled sand texture</b> — a procedural grain/ripple texture (supplied by
 *       the caller) modulated over the surface so it never reads as flat CG facets.</li>
 *   <li><b>Ambient occlusion</b> — valleys and hollows are darkened, dune crests
 *       lifted, using the local height curvature.</li>
 *   <li><b>Slope + colour variation</b> — steeper slip-faces shade darker, and a
 *       slow large-scale tint breaks up the uniformity of the sand.</li>
 * </ul>
 * Coordinate frame (replay world frame):
 * <ul>
 *   <li>GL X = east</li>
 *   <li>GL Y = altitude (0 = launch point elevation)</li>
 *   <li>GL Z = south = −north</li>
 * </ul>
 */
public final class TerrainRenderer {

    /** Sand texture tile size in metres (smaller = finer grain). */
    private static final double TEXTURE_TILE_METERS = 6.0;

    private TerrainRenderer() { }

    /** Draws the terrain with the natural (non-desert) ramp and no texture. */
    public static void render(GL2 gl, TerrainData t) {
        render(gl, t, false, 0);
    }

    /** Draws the terrain with a selectable ramp and no texture. */
    public static void render(GL2 gl, TerrainData t, boolean desert) {
        render(gl, t, desert, 0);
    }

    /**
     * Draw the terrain mesh.  Caller owns the surrounding GL state (projection,
     * modelview, lighting enable).  This method manages {@code GL_COLOR_MATERIAL},
     * texturing and the surface specular material locally and restores them on exit.
     *
     * @param gl        the GL context
     * @param t         the elevation grid to draw
     * @param desert    {@code true} for the sand ramp, {@code false} for green→rock→snow
     * @param textureId a 2D sand texture name to modulate over the surface, or 0 for none
     */
    public static void render(GL2 gl, TerrainData t, boolean desert, int textureId) {
        if (t == null || t.gridSize < 2) {
            return;
        }

        final int n = t.gridSize;
        final double spacing = t.spacingMeters;

        // Pre-compute GL-frame vertex coordinates.
        final double[][] vx = new double[n][n];
        final double[][] vy = new double[n][n];
        final double[][] vz = new double[n][n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                vx[r][c] = t.eastOffset(c);
                vy[r][c] = t.relativeElevation(r, c);
                vz[r][c] = -t.northOffset(r);
            }
        }

        // Per-vertex normals via central differences (smooth shading).
        final double[][] nx = new double[n][n];
        final double[][] ny = new double[n][n];
        final double[][] nz = new double[n][n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int cp = Math.min(c + 1, n - 1);
                int cm = Math.max(c - 1, 0);
                int rp = Math.min(r + 1, n - 1);
                int rm = Math.max(r - 1, 0);

                double dx = vx[r][cp] - vx[r][cm];
                double dyEast = vy[r][cp] - vy[r][cm];
                double dyX = (Math.abs(dx) > 1e-9) ? dyEast / dx : 0.0;

                double dz = vz[rp][c] - vz[rm][c];
                double dyNorth = vy[rp][c] - vy[rm][c];
                double dyZ = (Math.abs(dz) > 1e-9) ? dyNorth / dz : 0.0;

                double ax = -dyX;
                double ay = 1.0;
                double az = -dyZ;
                double len = Math.sqrt(ax * ax + ay * ay + az * az);
                if (len < 1e-12) {
                    len = 1.0;
                }
                nx[r][c] = ax / len;
                ny[r][c] = ay / len;
                nz[r][c] = az / len;
            }
        }

        // Pre-compute the final albedo per vertex (ramp × AO × slope × variation).
        final double elevRange = t.maxElevation - t.minElevation;
        final int aoRadius = Math.max(2, n / 64);   // broad curvature kernel
        // Flat RGB buffer (3 floats per vertex) — avoids allocating n*n tiny arrays.
        final float[] col = new float[n * n * 3];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                double tNorm = (elevRange > 1e-6)
                        ? (t.elevation[r][c] - t.minElevation) / elevRange : 0.0;
                float[] base = desert ? desertColor(tNorm) : elevationColor(tNorm);

                // Ambient occlusion: positive curvature (hollow) darkens, negative
                // (crest) lifts slightly.  Curvature ≈ neighbour-average − centre.
                int cp = Math.min(c + aoRadius, n - 1);
                int cm = Math.max(c - aoRadius, 0);
                int rp = Math.min(r + aoRadius, n - 1);
                int rm = Math.max(r - aoRadius, 0);
                double neigh = 0.25 * (t.elevation[r][cp] + t.elevation[r][cm]
                        + t.elevation[rp][c] + t.elevation[rm][c]);
                double curvature = (neigh - t.elevation[r][c]) / (aoRadius * spacing);
                double ao = clamp(1.0 - Math.max(0.0, curvature) * 1.3, 0.62, 1.0)
                        * clamp(1.0 + Math.max(0.0, -curvature) * 0.30, 1.0, 1.12);

                // Slip-faces (steeper slopes) read a touch darker.
                double slopeShade = 0.85 + 0.15 * ny[r][c];

                // Slow large-scale tint so the sand isn't a uniform sheet.
                double var = 0.5 + 0.5 * Math.sin(vx[r][c] * 0.013 + 1.7)
                        * Math.cos(vz[r][c] * 0.011 + 0.4);
                double bright = 0.95 + 0.10 * var;
                double warm = 1.0 + (var - 0.5) * 0.05;
                double cool = 1.0 - (var - 0.5) * 0.05;

                double f = ao * slopeShade * bright;
                int ci = (r * n + c) * 3;
                col[ci]     = (float) clamp(base[0] * f * warm, 0.0, 1.0);
                col[ci + 1] = (float) clamp(base[1] * f, 0.0, 1.0);
                col[ci + 2] = (float) clamp(base[2] * f * cool, 0.0, 1.0);
            }
        }

        gl.glEnable(GL2.GL_COLOR_MATERIAL);
        gl.glColorMaterial(GL.GL_FRONT_AND_BACK, GL2.GL_AMBIENT_AND_DIFFUSE);

        // Subtle specular so sunlit crests catch a sheen (separated so the texture
        // doesn't dull the highlight — see GL_SEPARATE_SPECULAR_COLOR in the panel).
        gl.glMaterialfv(GL.GL_FRONT_AND_BACK, GL2.GL_SPECULAR, new float[]{ 0.12f, 0.12f, 0.10f, 1f }, 0);
        gl.glMaterialf(GL.GL_FRONT_AND_BACK, GL2.GL_SHININESS, 6.0f);

        boolean textured = textureId != 0;
        if (textured) {
            gl.glEnable(GL.GL_TEXTURE_2D);
            gl.glBindTexture(GL.GL_TEXTURE_2D, textureId);
            gl.glTexEnvi(GL2.GL_TEXTURE_ENV, GL2.GL_TEXTURE_ENV_MODE, GL2.GL_MODULATE);
        }

        gl.glBegin(GL2.GL_TRIANGLES);
        for (int r = 0; r < n - 1; r++) {
            for (int c = 0; c < n - 1; c++) {
                emit(gl, r, c, n, vx, vy, vz, nx, ny, nz, col, textured);
                emit(gl, r + 1, c, n, vx, vy, vz, nx, ny, nz, col, textured);
                emit(gl, r + 1, c + 1, n, vx, vy, vz, nx, ny, nz, col, textured);

                emit(gl, r, c, n, vx, vy, vz, nx, ny, nz, col, textured);
                emit(gl, r + 1, c + 1, n, vx, vy, vz, nx, ny, nz, col, textured);
                emit(gl, r, c + 1, n, vx, vy, vz, nx, ny, nz, col, textured);
            }
        }
        gl.glEnd();

        if (textured) {
            gl.glDisable(GL.GL_TEXTURE_2D);
        }
        gl.glMaterialfv(GL.GL_FRONT_AND_BACK, GL2.GL_SPECULAR, new float[]{ 0f, 0f, 0f, 1f }, 0);
        gl.glDisable(GL2.GL_COLOR_MATERIAL);
    }

    private static void emit(GL2 gl, int r, int c, int n,
                             double[][] vx, double[][] vy, double[][] vz,
                             double[][] nx, double[][] ny, double[][] nz,
                             float[] col, boolean textured) {
        int ci = (r * n + c) * 3;
        gl.glColor3f(col[ci], col[ci + 1], col[ci + 2]);
        gl.glNormal3d(nx[r][c], ny[r][c], nz[r][c]);
        if (textured) {
            gl.glTexCoord2d(vx[r][c] / TEXTURE_TILE_METERS, vz[r][c] / TEXTURE_TILE_METERS);
        }
        gl.glVertex3d(vx[r][c], vy[r][c], vz[r][c]);
    }

    /**
     * Maps a normalized elevation [0,1] to a natural terrain colour ramp:
     * green lowlands → tan/brown slopes → grey rock → white peaks.
     */
    private static float[] elevationColor(double tNorm) {
        double v = Math.max(0.0, Math.min(1.0, tNorm));
        float[] green = { 0.20f, 0.45f, 0.15f };
        float[] tan   = { 0.45f, 0.38f, 0.23f };
        float[] grey  = { 0.50f, 0.50f, 0.52f };
        float[] white = { 0.90f, 0.90f, 0.93f };

        if (v < 0.45) {
            return lerp(green, tan, v / 0.45);
        } else if (v < 0.75) {
            return lerp(tan, grey, (v - 0.45) / 0.30);
        } else {
            return lerp(grey, white, (v - 0.75) / 0.25);
        }
    }

    /**
     * Maps a normalized elevation [0,1] to a desaturated, earthy sand ramp.
     * Lighting and the texture supply the warmth and detail; baking it into the
     * albedo too is what made the original ramp look like Mars.
     */
    private static float[] desertColor(double tNorm) {
        double v = Math.max(0.0, Math.min(1.0, tNorm));
        float[] low   = { 0.52f, 0.47f, 0.37f }; // shaded valley sand
        float[] mid   = { 0.74f, 0.68f, 0.53f }; // typical sunlit sand
        float[] crest = { 0.87f, 0.82f, 0.69f }; // pale dune crest
        if (v < 0.55) {
            return lerp(low, mid, v / 0.55);
        }
        return lerp(mid, crest, (v - 0.55) / 0.45);
    }

    private static float[] lerp(float[] a, float[] b, double f) {
        float ff = (float) Math.max(0.0, Math.min(1.0, f));
        return new float[] {
                a[0] + (b[0] - a[0]) * ff,
                a[1] + (b[1] - a[1]) * ff,
                a[2] + (b[2] - a[2]) * ff
        };
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
