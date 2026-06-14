package info.openrocket.core.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Fetches real atmospheric conditions from the NOAA Weather API (api.weather.gov).
 * <p>
 * Coverage is limited to the continental United States and some territories.
 * All fetch methods return {@code null} on any network or parse error so callers
 * can gracefully fall back to the configured wind model.
 * <p>
 * The surface wind from the NOAA hourly forecast is extended to altitude using
 * the Hellmann power-law profile (α = 1/7, open terrain).
 */
public final class WeatherFetcher {

    private static final Logger log = LoggerFactory.getLogger(WeatherFetcher.class);

    private static final String NOAA_POINTS_URL = "https://api.weather.gov/points/%.4f,%.4f";
    private static final String OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=%.4f&longitude=%.4f"
            + "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,wind_direction_10m"
            + "&hourly=wind_speed_10m,wind_speed_80m,wind_speed_120m,wind_speed_180m,"
            + "wind_direction_10m,wind_direction_80m,wind_direction_120m,wind_direction_180m"
            + "&wind_speed_unit=ms&forecast_days=1";
    private static final String USER_AGENT = "OpenRocket/unstable weather integration (openrocket.info)";
    private static final int TIMEOUT_MS = 8000;

    /** Measurement heights (m AGL) reported by the Open-Meteo wind profile. */
    private static final double[] OPEN_METEO_HEIGHTS = {10, 80, 120, 180};
    /** Altitudes (m AGL) above the highest measured level, extrapolated via Hellmann. */
    private static final double[] EXTRAPOLATED_ALTITUDES = {300, 600, 1000, 2000, 3000};

    /** Hellmann exponent for open terrain (1/7 law). */
    private static final double HELLMANN_ALPHA = 1.0 / 7.0;
    /** Reference height for surface wind speed (m). */
    private static final double REF_HEIGHT_M = 10.0;
    /** Altitude levels (m AGL) at which wind profile points are generated. */
    private static final double[] PROFILE_ALTITUDES_M = {0, 100, 300, 500, 1000, 2000, 3000};

    private WeatherFetcher() { }

    // -------------------------------------------------------------------------
    // Public data types
    // -------------------------------------------------------------------------

    /** Wind speed and direction at one altitude level. */
    public static final class WeatherLevel {
        /** Altitude above ground level (m). */
        public final double altitudeAGL;
        /** Wind speed (m/s). */
        public final double windSpeedMps;
        /** Meteorological wind direction in radians (direction wind blows FROM, CW from North). */
        public final double windDirectionRad;

        WeatherLevel(double altitudeAGL, double windSpeedMps, double windDirectionRad) {
            this.altitudeAGL = altitudeAGL;
            this.windSpeedMps = windSpeedMps;
            this.windDirectionRad = windDirectionRad;
        }
    }

    /** Weather observation fetched from NOAA, ready to load into a wind model. */
    public static final class WeatherData {
        /** Altitude-varying wind profile derived from the surface forecast. */
        public final List<WeatherLevel> windLevels;
        /** Surface temperature in Kelvin. */
        public final double temperatureK;
        /** Relative humidity (0–1). */
        public final double relativeHumidity;
        /** Human-readable source of this data ("Open-Meteo", "NOAA", "offline estimate"). */
        public final String source;
        /** True if fetched from a live API; false for the synthetic offline fallback. */
        public final boolean live;

        WeatherData(List<WeatherLevel> windLevels, double temperatureK, double relativeHumidity) {
            this(windLevels, temperatureK, relativeHumidity, "live", true);
        }

        WeatherData(List<WeatherLevel> windLevels, double temperatureK, double relativeHumidity,
                String source, boolean live) {
            this.windLevels = windLevels;
            this.temperatureK = temperatureK;
            this.relativeHumidity = relativeHumidity;
            this.source = source;
            this.live = live;
        }

        /** Compass label (N, NE, ...) for a meteorological "from" direction in radians. */
        private static String compass(double rad) {
            String[] pts = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            int i = (int) Math.round(((Math.toDegrees(rad) % 360 + 360) % 360) / 45.0) % 8;
            return pts[i];
        }

        /** One-line human summary of the surface conditions and source. */
        public String describe() {
            if (windLevels == null || windLevels.isEmpty()) {
                return "No wind data";
            }
            WeatherLevel sfc = windLevels.get(0);
            return String.format(Locale.ROOT,
                    "%.1f m/s from %s, %.0f°C, %d levels (%s)",
                    sfc.windSpeedMps, compass(sfc.windDirectionRad),
                    temperatureK - 273.15, windLevels.size(), source);
        }
    }

    /**
     * Builds a synthetic, clearly-labelled desert wind profile for use when no
     * live weather source can be reached (e.g. offline).  Light, warm, dry
     * conditions with wind increasing with altitude via the Hellmann law.
     *
     * @param latitudeDeg  launch latitude (used only to vary the surface speed slightly)
     * @param longitudeDeg launch longitude (unused, kept for signature symmetry)
     * @return a non-null {@link WeatherData} flagged as {@code live = false}
     */
    public static WeatherData syntheticFallback(double latitudeDeg, double longitudeDeg) {
        // Gentle, repeatable surface breeze ~3–5 m/s from the west-southwest.
        double surfaceSpeed = 3.0 + 2.0 * Math.abs(Math.sin(Math.toRadians(latitudeDeg) * 3.0));
        double dirRad = Math.toRadians(247.5); // WSW
        List<WeatherLevel> levels = buildWindProfile(surfaceSpeed, dirRad);
        return new WeatherData(levels, 305.15 /* ~32 °C */, 0.12, "offline desert estimate", false);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetch current weather for the given location.
     * <p>
     * Open-Meteo is tried first (global coverage, real multi-height wind profile).
     * If it fails, NOAA is tried as a fallback (US coverage, surface wind extended
     * to altitude via the Hellmann power law).
     *
     * @param latitudeDeg  launch site latitude in degrees (positive = North)
     * @param longitudeDeg launch site longitude in degrees (positive = East)
     * @return populated {@link WeatherData}, or {@code null} if every source fails
     */
    public static WeatherData fetch(double latitudeDeg, double longitudeDeg) {
        WeatherData data = fetchOpenMeteo(latitudeDeg, longitudeDeg);
        if (data != null) {
            return data;
        }
        log.info("Open-Meteo returned no data; falling back to NOAA");
        return fetchNoaa(latitudeDeg, longitudeDeg);
    }

    /**
     * Fetch weather from the Open-Meteo API (global coverage, no API key).
     *
     * @param latitudeDeg  launch site latitude in degrees
     * @param longitudeDeg launch site longitude in degrees
     * @return populated {@link WeatherData}, or {@code null} on any error
     */
    public static WeatherData fetchOpenMeteo(double latitudeDeg, double longitudeDeg) {
        try {
            String url = String.format(Locale.ROOT, OPEN_METEO_URL, latitudeDeg, longitudeDeg);
            log.info("Fetching Open-Meteo weather for ({}, {})", latitudeDeg, longitudeDeg);

            String json = httpGet(url);
            if (json == null) {
                return null;
            }
            return parseOpenMeteo(json);
        } catch (Exception e) {
            log.warn("Failed to fetch Open-Meteo weather data: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetch weather from the NOAA Weather API (US coverage only).
     *
     * @param latitudeDeg  launch site latitude in degrees
     * @param longitudeDeg launch site longitude in degrees
     * @return populated {@link WeatherData}, or {@code null} on any error
     */
    public static WeatherData fetchNoaa(double latitudeDeg, double longitudeDeg) {
        try {
            String pointsUrl = String.format(Locale.ROOT, NOAA_POINTS_URL, latitudeDeg, longitudeDeg);
            log.info("Fetching NOAA weather for ({}, {})", latitudeDeg, longitudeDeg);

            String pointsJson = httpGet(pointsUrl);
            if (pointsJson == null) {
                return null;
            }

            String hourlyUrl = extractHourlyUrl(pointsJson);
            if (hourlyUrl == null) {
                log.warn("Could not extract forecastHourly URL from NOAA points response");
                return null;
            }

            String forecastJson = httpGet(hourlyUrl);
            if (forecastJson == null) {
                return null;
            }

            return parseForecast(forecastJson);

        } catch (Exception e) {
            log.warn("Failed to fetch NOAA weather data: {}", e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Internal: HTTP
    // -------------------------------------------------------------------------

    private static String httpGet(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/geo+json,application/json");

            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                log.warn("NOAA API returned HTTP {} for: {}", status, urlString);
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

    private static String extractHourlyUrl(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject props = root.getAsJsonObject("properties");
            if (props == null) {
                return null;
            }
            JsonElement hourlyEl = props.get("forecastHourly");
            return hourlyEl != null && !hourlyEl.isJsonNull() ? hourlyEl.getAsString() : null;
        } catch (JsonSyntaxException | IllegalStateException e) {
            log.warn("Failed to parse NOAA points JSON: {}", e.getMessage());
            return null;
        }
    }

    private static WeatherData parseForecast(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject props = root.getAsJsonObject("properties");
            if (props == null) {
                return null;
            }
            JsonArray periods = props.getAsJsonArray("periods");
            if (periods == null || periods.size() == 0) {
                log.warn("No forecast periods in NOAA hourly response");
                return null;
            }

            JsonObject period = periods.get(0).getAsJsonObject();

            double windSpeedMps = parseWindSpeedMps(getString(period, "windSpeed"));
            double windDirRad = parseWindDirectionRad(getString(period, "windDirection"));
            double tempK = parseTemperatureK(period);
            double humidity = parseHumidity(period);

            List<WeatherLevel> levels = buildWindProfile(windSpeedMps, windDirRad);
            log.info("NOAA weather: wind {} m/s from {} ({} rad), temp {} K",
                    String.format("%.1f", windSpeedMps), getString(period, "windDirection"),
                    String.format("%.2f", windDirRad), String.format("%.1f", tempK));
            return new WeatherData(levels, tempK, humidity, "NOAA", true);

        } catch (JsonSyntaxException | IllegalStateException e) {
            log.warn("Failed to parse NOAA forecast JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parses an Open-Meteo forecast response into a {@link WeatherData}.
     * <p>
     * Uses the four measured wind heights (10, 80, 120, 180 m) directly and
     * extrapolates to higher altitudes from the topmost measured level via the
     * Hellmann power law.
     */
    private static WeatherData parseOpenMeteo(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject hourly = root.getAsJsonObject("hourly");
            if (hourly == null) {
                log.warn("Open-Meteo response missing 'hourly' block");
                return null;
            }

            List<WeatherLevel> levels = new ArrayList<>();
            double topSpeed = 0.0;
            double topDir = 0.0;
            for (double height : OPEN_METEO_HEIGHTS) {
                int h = (int) height;
                double speed = firstValue(hourly, "wind_speed_" + h + "m");
                double dirDeg = firstValue(hourly, "wind_direction_" + h + "m");
                if (Double.isNaN(speed) || Double.isNaN(dirDeg)) {
                    continue;
                }
                double dirRad = Math.toRadians(dirDeg);
                levels.add(new WeatherLevel(height, speed, dirRad));
                topSpeed = speed;
                topDir = dirRad;
            }

            if (levels.isEmpty()) {
                log.warn("Open-Meteo response had no usable wind levels");
                return null;
            }

            // Extrapolate above the highest measured level (180 m) using the Hellmann law.
            double topHeight = OPEN_METEO_HEIGHTS[OPEN_METEO_HEIGHTS.length - 1];
            for (double alt : EXTRAPOLATED_ALTITUDES) {
                double speed = topSpeed * Math.pow(alt / topHeight, HELLMANN_ALPHA);
                levels.add(new WeatherLevel(alt, speed, topDir));
            }

            // Current conditions for temperature / humidity.
            double tempK = 293.15;
            double humidity = 0.5;
            JsonObject current = root.getAsJsonObject("current");
            if (current != null) {
                JsonElement tEl = current.get("temperature_2m");
                if (tEl != null && !tEl.isJsonNull()) {
                    tempK = tEl.getAsDouble() + 273.15;
                }
                JsonElement hEl = current.get("relative_humidity_2m");
                if (hEl != null && !hEl.isJsonNull()) {
                    humidity = hEl.getAsDouble() / 100.0;
                }
            }

            log.info("Open-Meteo weather: {} levels, surface {} m/s, temp {} K",
                    levels.size(), String.format("%.1f", levels.get(0).windSpeedMps),
                    String.format("%.1f", tempK));
            return new WeatherData(levels, tempK, humidity, "Open-Meteo", true);

        } catch (JsonSyntaxException | IllegalStateException e) {
            log.warn("Failed to parse Open-Meteo JSON: {}", e.getMessage());
            return null;
        }
    }

    /** Returns the first element of an Open-Meteo hourly array, or NaN if absent. */
    private static double firstValue(JsonObject hourly, String key) {
        try {
            JsonArray arr = hourly.getAsJsonArray(key);
            if (arr == null || arr.size() == 0 || arr.get(0).isJsonNull()) {
                return Double.NaN;
            }
            return arr.get(0).getAsDouble();
        } catch (IllegalStateException | NumberFormatException e) {
            return Double.NaN;
        }
    }

    // -------------------------------------------------------------------------
    // Internal: unit conversions
    // -------------------------------------------------------------------------

    /**
     * Parses NOAA wind speed string to m/s.
     * Handles formats: "10 mph", "10 to 20 mph", "15 km/h".
     */
    static double parseWindSpeedMps(String speedStr) {
        if (speedStr == null || speedStr.trim().isEmpty()) {
            return 5.0;
        }
        String s = speedStr.trim();
        try {
            // "10 to 20 mph" → average
            if (s.contains(" to ")) {
                String[] parts = s.split("\\s+to\\s+");
                double low = parseNumber(parts[0].trim());
                double high = parseNumber(parts[1].trim().split("\\s+")[0]);
                double avgMph = (low + high) / 2.0;
                return s.toLowerCase(java.util.Locale.ROOT).contains("mph")
                        ? avgMph * 0.44704 : avgMph / 3.6;
            }
            // "10 mph" or "10 km/h"
            String[] parts = s.split("\\s+");
            double value = Double.parseDouble(parts[0]);
            if (parts.length > 1 && parts[1].equalsIgnoreCase("mph")) {
                return value * 0.44704;
            }
            // assume km/h or m/s if no unit / unknown
            if (parts.length > 1 && (parts[1].equalsIgnoreCase("km/h") || parts[1].equalsIgnoreCase("kph"))) {
                return value / 3.6;
            }
            // Bare number: assume m/s
            return value;
        } catch (NumberFormatException e) {
            log.warn("Cannot parse wind speed '{}': {}", speedStr, e.getMessage());
            return 5.0;
        }
    }

    /** Converts NOAA compass direction abbreviation to radians (meteorological: from). */
    static double parseWindDirectionRad(String dirStr) {
        if (dirStr == null) {
            return 0.0;
        }
        switch (dirStr.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "N":   return 0.0;
            case "NNE": return Math.PI / 8.0;
            case "NE":  return Math.PI / 4.0;
            case "ENE": return 3.0 * Math.PI / 8.0;
            case "E":   return Math.PI / 2.0;
            case "ESE": return 5.0 * Math.PI / 8.0;
            case "SE":  return 3.0 * Math.PI / 4.0;
            case "SSE": return 7.0 * Math.PI / 8.0;
            case "S":   return Math.PI;
            case "SSW": return 9.0 * Math.PI / 8.0;
            case "SW":  return 5.0 * Math.PI / 4.0;
            case "WSW": return 11.0 * Math.PI / 8.0;
            case "W":   return 3.0 * Math.PI / 2.0;
            case "WNW": return 13.0 * Math.PI / 8.0;
            case "NW":  return 7.0 * Math.PI / 4.0;
            case "NNW": return 15.0 * Math.PI / 8.0;
            default:
                log.warn("Unknown wind direction string: '{}'", dirStr);
                return 0.0;
        }
    }

    private static double parseTemperatureK(JsonObject period) {
        try {
            double val = period.get("temperature").getAsDouble();
            String unit = getString(period, "temperatureUnit");
            if ("F".equalsIgnoreCase(unit)) {
                return (val - 32.0) * 5.0 / 9.0 + 273.15;
            }
            // Celsius (NOAA uses °F for US but may return °C)
            return val + 273.15;
        } catch (NullPointerException | IllegalStateException | NumberFormatException e) {
            return 293.15; // ISA standard
        }
    }

    private static double parseHumidity(JsonObject period) {
        try {
            JsonObject humidity = period.getAsJsonObject("relativeHumidity");
            if (humidity != null) {
                JsonElement val = humidity.get("value");
                if (val != null && !val.isJsonNull()) {
                    return val.getAsDouble() / 100.0;
                }
            }
        } catch (IllegalStateException | NumberFormatException e) {
            // ignore
        }
        return 0.5; // assume 50 % if missing
    }

    /**
     * Builds altitude-varying wind profile using the Hellmann power-law:
     * {@code v(z) = v_ref × (z / z_ref)^α}.
     */
    private static List<WeatherLevel> buildWindProfile(double surfaceSpeedMps, double dirRad) {
        List<WeatherLevel> levels = new ArrayList<>(PROFILE_ALTITUDES_M.length);
        for (double alt : PROFILE_ALTITUDES_M) {
            double speed;
            if (alt <= REF_HEIGHT_M) {
                speed = surfaceSpeedMps;
            } else {
                speed = surfaceSpeedMps * Math.pow(alt / REF_HEIGHT_M, HELLMANN_ALPHA);
            }
            levels.add(new WeatherLevel(alt, speed, dirRad));
        }
        return levels;
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private static String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull()) ? null : el.getAsString();
    }

    private static double parseNumber(String s) {
        return Double.parseDouble(s.replaceAll("[^0-9.]", ""));
    }
}
