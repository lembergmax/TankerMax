package de.lembergmax.tankermax.forecast.ml;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests für {@link PriceGridResampler}: das Vorwärtsfüllen der Treppenfunktion auf ein gleichmäßiges
 * Stundenraster sowie die zeitbasierten Zugriffe des erzeugten {@link HourlyGrid}.
 */
class PriceGridResamplerTest {

    /** Rasterabstand von einer Stunde. */
    private static final long HOUR = 3600;

    /**
     * Beobachtungen werden auf das Stundenraster gelegt und der zuletzt bekannte Preis vorwärts gefüllt.
     */
    @Test
    void vorwaertsFuellenAufStundenraster() {
        final StationObservations observations = new StationObservations(
                new StationMeta("s1", "01", "Sachsen", "Aral"),
                new long[]{0, 2 * HOUR},
                new double[]{1.000, 1.500});

        final HourlyGrid grid = PriceGridResampler.resample(observations, 3 * HOUR, HOUR);

        assertThat(grid.size()).isEqualTo(4);
        assertThat(grid.priceAt(0)).isEqualTo(1.000);
        assertThat(grid.priceAt(1)).isEqualTo(1.000);
        assertThat(grid.priceAt(2)).isEqualTo(1.500);
        assertThat(grid.lastPrice()).isEqualTo(1.500);
    }

    /**
     * Die zeitbasierten Zugriffe liefern den richtigen Rasterpreis und erkennen den abgedeckten Bereich.
     */
    @Test
    void zeitbasierteZugriffe() {
        final StationObservations observations = new StationObservations(
                new StationMeta("s1", "01", null, ""),
                new long[]{0, 2 * HOUR},
                new double[]{1.000, 1.500});

        final HourlyGrid grid = PriceGridResampler.resample(observations, 3 * HOUR, HOUR);

        assertThat(grid.coversTime(HOUR)).isTrue();
        assertThat(grid.coversTime(10 * HOUR)).isFalse();
        assertThat(grid.priceAtTime(HOUR)).isEqualTo(1.000);
        assertThat(grid.priceAtTime(2 * HOUR)).isEqualTo(1.500);
    }

}
