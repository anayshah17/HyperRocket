package info.openrocket.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests the offline fallback and human-readable summary used by the "Fetch Now"
 * weather button, so it always produces transparent, real numbers even with no
 * internet connection.
 */
public class WeatherFetcherTest {

    @Test
    public void testSyntheticFallbackAlwaysUsable() {
        WeatherFetcher.WeatherData w = WeatherFetcher.syntheticFallback(40.78, -119.21);
        assertNotNull(w, "fallback must never be null");
        assertFalse(w.live, "fallback must be flagged as not live");
        assertFalse(w.windLevels.isEmpty(), "fallback must provide wind levels");
        assertTrue(w.temperatureK > 250 && w.temperatureK < 330, "plausible temperature");

        // Surface wind speed positive; wind increases with altitude (Hellmann law).
        WeatherFetcher.WeatherLevel surface = w.windLevels.get(0);
        WeatherFetcher.WeatherLevel top = w.windLevels.get(w.windLevels.size() - 1);
        assertTrue(surface.windSpeedMps > 0, "positive surface wind");
        assertTrue(top.windSpeedMps >= surface.windSpeedMps, "wind grows with altitude");
    }

    @Test
    public void testDescribeShowsNumbersAndSource() {
        WeatherFetcher.WeatherData w = WeatherFetcher.syntheticFallback(35.0, -110.0);
        String desc = w.describe();
        assertNotNull(desc);
        assertTrue(desc.contains("m/s"), "should report wind speed: " + desc);
        assertTrue(desc.contains("°C"), "should report temperature: " + desc);
        assertTrue(desc.toLowerCase(java.util.Locale.ROOT).contains("offline"),
                "should disclose the offline source: " + desc);
    }

    @Test
    public void testWindSpeedParsingUnits() {
        assertEquals(4.4704, WeatherFetcher.parseWindSpeedMps("10 mph"), 0.01);
        assertEquals(2.7778, WeatherFetcher.parseWindSpeedMps("10 km/h"), 0.01);
        // "10 to 20 mph" → average 15 mph ≈ 6.7 m/s
        assertEquals(6.7056, WeatherFetcher.parseWindSpeedMps("10 to 20 mph"), 0.05);
    }
}
