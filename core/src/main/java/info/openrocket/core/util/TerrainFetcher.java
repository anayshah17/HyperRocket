package info.openrocket.core.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Fetches a Digital Elevation Model (DEM) grid around a launch site from the
 * public Open-Elevation API (api.open-elevation.com).
 * <p>
 * A square grid of sample points is built around the centre coordinate, posted
 * to the batch lookup endpoint in a single request, and returned as a 2-D
 * elevation array centred on the launch point.  The fetch returns {@code null}
 * on any network or parse error so callers can fall back to a flat ground plane.
 * <p>
 * Grid indexing convention (matches the 3D replay GL frame):
 * <ul>
 *   <li>row 0 = southmost (north offset −radius), row N−1 = northmost</li>
 *   <li>col 0 = westmost (east offset −radius), col N−1 = eastmost</li>
 * </ul>
 */
public final class TerrainFetcher {

    private static final Logger log = LoggerFactory.getLogger(TerrainFetcher.class);

    private static final String LOOKUP_URL = "https://api.open-elevation.com/api/v1/lookup";
    private static final String USER_AGENT = "OpenRocket/unstable terrain integration (openrocket.info)";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 45000;

    /** Approximate metres per degree of latitude (WGS84 mean). */
    private static final double METERS_PER_DEG_LAT = 111320.0;
    /** Largest grid we will request in a single batch (points per side). */
    private static final int MAX_GRID_SIZE = 48;

    private TerrainFetcher() { }

    // -------------------------------------------------------------------------
    // Public data type
    // -------------------------------------------------------------------------

    /** A square elevation grid centred on the launch site. */
    public static final class TerrainData {
        /** Number of sample points per side (grid is gridSize × gridSize). */
        public final int gridSize;
        /** Horizontal spacing between adjacent grid points (m). */
        public final double spacingMeters;
        /** Elevation in metres MSL, indexed {@code [row][col]} (row = north index, col = east index). */
        public final double[][] elevation;
        /** Elevation at the centre / launch point (m MSL). */
        public final double centerElevation;
        /** Minimum elevation in the grid (m MSL). */
        public final double minElevation;
        /** Maximum elevation in the grid (m MSL). */
        public final double maxElevation;

        TerrainData(int gridSize, double spacingMeters, double[][] elevation,
                    double centerElevation, double minElevation, double maxElevation) {
            this.gridSize = gridSize;
            this.spacingMeters = spacingMeters;
            this.elevation = elevation;
            this.centerElevation = centerElevation;
            this.minElevation = minElevation;
            this.maxElevation = maxElevation;
        }

        /**
         * East offset (m, +east) of the given column relative to the launch point.
         */
        public double eastOffset(int col) {
            return (col - (gridSize - 1) / 2.0) * spacingMeters;
        }

        /**
         * North offset (m, +north) of the given row relative to the launch point.
         */
        public double northOffset(int row) {
            return (row - (gridSize - 1) / 2.0) * spacingMeters;
        }

        /** Elevation relative to the launch point (m, 0 = launch altitude). */
        public double relativeElevation(int row, int col) {
            return elevation[row][col] - centerElevation;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetch a DEM grid centred on the given launch coordinate.
     *
     * @param centerLatDeg  launch latitude in degrees (positive = North)
     * @param centerLonDeg  launch longitude in degrees (positive = East)
     * @param radiusMeters  half-width of the square terrain patch (m); the grid
     *                      spans ±radiusMeters about the centre
     * @param gridSize      number of sample points per side (clamped to [2, 48])
     * @return populated {@link TerrainData}, or {@code null} on any network or
     *         parse error
     */
    public static TerrainData fetch(double centerLatDeg, double centerLonDeg,
                                    double radiusMeters, int gridSize) {
        int n = Math.max(2, Math.min(MAX_GRID_SIZE, gridSize));
        double radius = Math.max(1.0, radiusMeters);
        double spacing = (2.0 * radius) / (n - 1);

        double cosLat = Math.cos(Math.toRadians(centerLatDeg));
        double metersPerDegLon = METERS_PER_DEG_LAT * Math.max(1e-6, Math.abs(cosLat));

        try {
            String requestBody = buildRequestBody(centerLatDeg, centerLonDeg, n, spacing,
                    metersPerDegLon);
            log.info("Fetching terrain DEM for ({}, {}), {} m radius, {}×{} grid",
                    centerLatDeg, centerLonDeg, String.format("%.0f", radius), n, n);

            String json = httpPost(LOOKUP_URL, requestBody);
            if (json == null) {
                return null;
            }
            return parseGrid(json, n, spacing);
        } catch (Exception e) {
            log.warn("Failed to fetch terrain DEM: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generates a high-resolution procedural desert terrain locally — no network
     * required.  Produces rolling sand dunes with fine ripples and a flattened
     * launch-pad area at the centre, suitable as the default ground for the 3D
     * flight replay.
     *
     * @param radiusMeters half-width of the square patch (m); the grid spans ±radius
     * @param gridSize     number of sample points per side (e.g. 128 for high res)
     * @param seed         seed for repeatable dune placement
     * @return a populated {@link TerrainData}, never {@code null}
     */
    public static TerrainData generateDesert(double radiusMeters, int gridSize, long seed) {
        int n = Math.max(2, gridSize);
        double radius = Math.max(1.0, radiusMeters);
        double spacing = (2.0 * radius) / (n - 1);

        // Dune relief scales with the patch size so the desert reads as rolling
        // dunes from altitude, but stays modest near the launch site.
        double duneHeight = Math.min(30.0, Math.max(6.0, radius * 0.03));

        // The launch site is a flat, gently RAISED graded clearing (a prepared
        // pad), with the surrounding dunes sloped smoothly down to meet it.  This
        // is what stops the centre from reading as a pit: the pad sits slightly
        // above its surroundings rather than being punched down to zero while the
        // dunes tower around it.
        double padRadius = Math.max(30.0, radius * 0.045);   // flat cleared pad
        double apronRadius = padRadius * 3.0;                 // graded apron out to dunes
        double padLevel = duneHeight * 0.12;                  // pad sits gently raised

        double[][] elevation = new double[n][n];
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double mid = (n - 1) / 2.0;

        for (int row = 0; row < n; row++) {
            double north = (row - mid) * spacing;
            for (int col = 0; col < n; col++) {
                double east = (col - mid) * spacing;
                double h = duneElevation(east, north, duneHeight, seed);

                // Grade the clearing: flat pad inside padRadius, smooth (smoothstep)
                // transition to full dunes by apronRadius.
                double dist = Math.hypot(east, north);
                if (dist < apronRadius) {
                    double t = Math.max(0.0, Math.min(1.0,
                            (dist - padRadius) / (apronRadius - padRadius)));
                    double blend = t * t * (3 - 2 * t); // smoothstep
                    h = padLevel + (h - padLevel) * blend;
                }

                elevation[row][col] = h;
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }

        double centerElev = elevation[(n - 1) / 2][(n - 1) / 2];
        log.info("Generated procedural desert terrain: {}×{} grid, {} m radius, relief {}–{} m",
                n, n, String.format("%.0f", radius), String.format("%.1f", min), String.format("%.1f", max));
        return new TerrainData(n, spacing, elevation, centerElev, min, max);
    }

    /** Layered value-noise dune height field (m) at the given east/north offset. */
    private static double duneElevation(double east, double north, double amplitude, long seed) {
        double h = 0.0;
        // A few octaves of smooth value noise, plus a directional component so dunes
        // form ridges (as wind-blown sand does).  Wavelengths are kept well above the
        // grid spacing to avoid aliasing into spiky noise.
        double[] wavelength = { 650.0, 300.0, 130.0, 70.0, 40.0 };
        double[] weight     = { 1.0, 0.55, 0.28, 0.15, 0.08 };
        double wsum = 0;
        for (int i = 0; i < wavelength.length; i++) {
            double f = 1.0 / wavelength[i];
            // Rotate the sampling axes per octave for less obvious gridding.
            double ang = 0.6 * i;
            double u = east * Math.cos(ang) - north * Math.sin(ang);
            double v = east * Math.sin(ang) + north * Math.cos(ang);
            h += weight[i] * valueNoise(u * f, v * f, seed + i * 1013L);
            wsum += weight[i];
        }
        h /= wsum;
        // Ridged dune crests: bias the field so dunes are rounded with sharper tops.
        double ridged = 0.6 * h + 0.4 * Math.sin(east / 130.0 + 0.7 * Math.sin(north / 95.0));
        return amplitude * ridged;
    }

    /** Smooth 2-D value noise in [-1, 1] using hashed lattice corners + cosine interp. */
    private static double valueNoise(double x, double y, long seed) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0;
        double fy = y - y0;
        double sx = fx * fx * (3 - 2 * fx); // smoothstep
        double sy = fy * fy * (3 - 2 * fy);

        double n00 = hashNoise(x0, y0, seed);
        double n10 = hashNoise(x0 + 1, y0, seed);
        double n01 = hashNoise(x0, y0 + 1, seed);
        double n11 = hashNoise(x0 + 1, y0 + 1, seed);

        double ix0 = n00 + sx * (n10 - n00);
        double ix1 = n01 + sx * (n11 - n01);
        return ix0 + sy * (ix1 - ix0);
    }

    /** Deterministic hash of integer lattice coords to [-1, 1]. */
    private static double hashNoise(int x, int y, long seed) {
        long h = seed;
        h = h * 6364136223846793005L + (x * 0x9E3779B1L);
        h ^= (h >>> 29);
        h = h * 6364136223846793005L + (y * 0x85EBCA77L);
        h ^= (h >>> 32);
        return ((h >>> 11) / (double) (1L << 52)) * 2.0 - 1.0;
    }

    // -------------------------------------------------------------------------
    // Internal: request building
    // -------------------------------------------------------------------------

    private static String buildRequestBody(double centerLat, double centerLon, int n,
                                           double spacing, double metersPerDegLon) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"locations\":[");
        boolean first = true;
        for (int row = 0; row < n; row++) {
            double northMeters = (row - (n - 1) / 2.0) * spacing;
            double lat = centerLat + northMeters / METERS_PER_DEG_LAT;
            for (int col = 0; col < n; col++) {
                double eastMeters = (col - (n - 1) / 2.0) * spacing;
                double lon = centerLon + eastMeters / metersPerDegLon;
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"latitude\":").append(String.format("%.6f", lat))
                        .append(",\"longitude\":").append(String.format("%.6f", lon))
                        .append('}');
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Internal: HTTP
    // -------------------------------------------------------------------------

    private static String httpPost(String urlString, String body) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                log.warn("Open-Elevation API returned HTTP {} for: {}", status, urlString);
                return null;
            }

            InputStream in = conn.getInputStream();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } finally {
            conn.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // Internal: JSON parsing
    // -------------------------------------------------------------------------

    private static TerrainData parseGrid(String json, int n, double spacing) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray results = root.getAsJsonArray("results");
            if (results == null || results.size() != n * n) {
                log.warn("Terrain response had {} points, expected {}",
                        results == null ? 0 : results.size(), n * n);
                return null;
            }

            double[][] elevation = new double[n][n];
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            int idx = 0;
            for (int row = 0; row < n; row++) {
                for (int col = 0; col < n; col++) {
                    JsonObject point = results.get(idx++).getAsJsonObject();
                    double elev = point.get("elevation").getAsDouble();
                    elevation[row][col] = elev;
                    min = Math.min(min, elev);
                    max = Math.max(max, elev);
                }
            }

            int mid = (n - 1) / 2;
            double centerElev = elevation[mid][mid];

            log.info("Terrain loaded: {} points, elevation {}–{} m MSL (centre {} m)",
                    n * n, String.format("%.0f", min), String.format("%.0f", max),
                    String.format("%.0f", centerElev));
            return new TerrainData(n, spacing, elevation, centerElev, min, max);

        } catch (JsonSyntaxException | IllegalStateException | NullPointerException e) {
            log.warn("Failed to parse terrain JSON: {}", e.getMessage());
            return null;
        }
    }
}
