package de.lembergmax.tankermax.forecast.ml;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests für {@link ForecastFeatures}: die feste Anzahl und Reihenfolge der Merkmale sowie die
 * korrekte Ableitung des Horizont-Merkmals.
 */
class ForecastFeaturesTest {

    /** Rasterabstand von einer Stunde. */
    private static final long HOUR = 3600;

    /**
     * Die Anzahl der Merkmalsnamen stimmt mit der gemeldeten Merkmalszahl überein.
     */
    @Test
    void namenUndAnzahlStimmenUeberein() {
        assertThat(ForecastFeatures.featureNames()).hasSize(ForecastFeatures.count());
    }

    /**
     * Der Merkmalsvektor hat die erwartete Länge und das erste Merkmal ist der Horizont in Minuten.
     */
    @Test
    void vektorlaengeUndHorizontMerkmal() {
        final ForecastContext ctx = buildContext();
        final double[] features = ForecastFeatures.build(ctx, "s1", 30, 5);

        assertThat(features).hasSize(ForecastFeatures.count());
        assertThat(features[0]).isEqualTo(5 * 60.0);
    }

    /**
     * Baut einen Kontext mit einer Tankstelle und 48 Stundenwerten.
     *
     * @return Kontext für die Feature-Bildung
     */
    private ForecastContext buildContext() {
        final int points = 48;
        final long[] epochs = new long[points];
        final double[] prices = new double[points];
        for (int i = 0; i < points; i++) {
            epochs[i] = (long) i * HOUR;
            prices[i] = 1.500 + 0.01 * (i % 5);
        }
        final StationObservations observations = new StationObservations(
                new StationMeta("s1", "01", "Sachsen", "Aral"), epochs, prices);
        return ForecastContext.build(List.of(observations), (long) (points - 1) * HOUR, HOUR);
    }

}
