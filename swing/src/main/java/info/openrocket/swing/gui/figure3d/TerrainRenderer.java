package info.openrocket.swing.gui.figure3d;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;

import info.openrocket.core.util.TerrainFetcher.TerrainData;

/**
 * Renders a {@link TerrainData} elevation grid as a lit, elevation-shaded mesh
 * in the 3D flight-replay GL frame.
 * <p>
 * The mesh is expressed in the replay's world frame:
 * <ul>
 *   <li>GL X = east</li>
 *   <li>GL Y = altitude (0 = launch point elevation)</li>
 *   <li>GL Z = south = −north</li>
 * </ul>
 * Heights are drawn relative to the launch point, so the terrain meets the
 * rocket's AGL altitude reference at the origin.
 */
public final class TerrainRenderer {

    private TerrainRenderer() { }

    /**
     * Draw the terrain mesh.  Caller is responsible for surrounding GL state
     * (projection, modelview, lighting enable).  This method enables
     * {@code GL_COLOR_MATERIAL} locally and restores it on exit.
     *
     * @param gl the GL context
     * @param t  the elevation grid to draw
     */
    public static void render(GL2 gl, TerrainData t) {
        render(gl, t, false);
    }

    /**
     * Draw the terrain mesh with a selectable colour ramp.
     *
     * @param gl     the GL context
     * @param t      the elevation grid to draw
     * @param desert if {@code true}, use a sand/dune colour ramp; otherwise the
     *               natural green→rock→snow ramp
     */
    public static void render(GL2 gl, TerrainData t, boolean desert) {
        if (t == null || t.gridSize < 2) {
            return;
        }

        final int n = t.gridSize;

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

                // Height-field normal: (-dy/dX, 1, -dy/dZ)
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

        double elevRange = t.maxElevation - t.minElevation;

        gl.glEnable(GL2.GL_COLOR_MATERIAL);
        gl.glColorMaterial(GL.GL_FRONT_AND_BACK, GL2.GL_AMBIENT_AND_DIFFUSE);

        gl.glBegin(GL2.GL_TRIANGLES);
        for (int r = 0; r < n - 1; r++) {
            for (int c = 0; c < n - 1; c++) {
                // Triangle 1: (r,c) (r+1,c) (r+1,c+1)
                emitVertex(gl, r, c, vx, vy, vz, nx, ny, nz, t, elevRange, desert);
                emitVertex(gl, r + 1, c, vx, vy, vz, nx, ny, nz, t, elevRange, desert);
                emitVertex(gl, r + 1, c + 1, vx, vy, vz, nx, ny, nz, t, elevRange, desert);

                // Triangle 2: (r,c) (r+1,c+1) (r,c+1)
                emitVertex(gl, r, c, vx, vy, vz, nx, ny, nz, t, elevRange, desert);
                emitVertex(gl, r + 1, c + 1, vx, vy, vz, nx, ny, nz, t, elevRange, desert);
                emitVertex(gl, r, c + 1, vx, vy, vz, nx, ny, nz, t, elevRange, desert);
            }
        }
        gl.glEnd();

        gl.glDisable(GL2.GL_COLOR_MATERIAL);

        // Launch point marker on the terrain surface.
        gl.glDisable(GL2.GL_LIGHTING);
        gl.glPointSize(7.0f);
        gl.glColor3f(1.0f, 0.9f, 0.0f);
        gl.glBegin(GL2.GL_POINTS);
        gl.glVertex3d(0, 0.5, 0);
        gl.glEnd();
        gl.glPointSize(1.0f);
        gl.glEnable(GL2.GL_LIGHTING);
    }

    private static void emitVertex(GL2 gl, int r, int c,
                                   double[][] vx, double[][] vy, double[][] vz,
                                   double[][] nx, double[][] ny, double[][] nz,
                                   TerrainData t, double elevRange, boolean desert) {
        double tNorm;
        if (elevRange > 1e-6) {
            tNorm = (t.elevation[r][c] - t.minElevation) / elevRange;
        } else {
            tNorm = 0.0;
        }
        float[] col = desert ? desertColor(tNorm) : elevationColor(tNorm);
        gl.glColor3f(col[0], col[1], col[2]);
        gl.glNormal3d(nx[r][c], ny[r][c], nz[r][c]);
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
     * Maps a normalized elevation [0,1] to a warm desert sand ramp:
     * shaded valley sand → mid sand → sunlit dune crest.
     */
    private static float[] desertColor(double tNorm) {
        double v = Math.max(0.0, Math.min(1.0, tNorm));
        float[] low   = { 0.62f, 0.47f, 0.28f }; // shaded, slightly reddish sand
        float[] mid   = { 0.82f, 0.68f, 0.44f }; // typical sand
        float[] crest = { 0.94f, 0.86f, 0.66f }; // bright sunlit crest
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
}
