package de.lembergmax.tankermax.forecast.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Tests für {@link HourlyGrid}.
 *
 * <p>Die Fensterstatistiken werden beim Aufbau des Rasters einmalig vorberechnet, damit die Bildung
 * mehrerer Millionen Merkmalsvektoren nicht je Beispiel über die Fenster laufen muss. Diese Tests
 * vergleichen die vorberechneten Werte gegen eine direkte, offensichtlich korrekte Berechnung – sonst
 * bliebe ein Fehler in der gleitenden Extremwertbildung unbemerkt und verfälschte still das Training.</p>
 */
class HourlyGridTest {

    /** Rasterabstand von einer Stunde. */
    private static final long HOUR = 3600;

    /** Rasterpunkte je Tagesfenster. */
    private static final int DAY = 24;

    /**
     * Das gleitende Tagesminimum und -maximum stimmt mit der direkten Berechnung überein.
     */
    @Test
    void tagesExtremaStimmenMitDirekterBerechnungUeberein() {
        final double[] prices = randomPrices(200);
        final HourlyGrid grid = new HourlyGrid(0L, HOUR, prices.clone());

        for (int index = 0; index < prices.length; index++) {
            final int start = Math.max(0, index - DAY + 1);
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (int i = start; i <= index; i++) {
                min = Math.min(min, prices[i]);
                max = Math.max(max, prices[i]);
            }
            assertThat(grid.dayMin(index)).as("Minimum bei %d", index).isEqualTo(min);
            assertThat(grid.dayMax(index)).as("Maximum bei %d", index).isEqualTo(max);
        }
    }

    /**
     * Das Fenstermittel stimmt mit der direkten Berechnung überein und wird am Rasteranfang auf die
     * verfügbaren Punkte abgeschnitten.
     */
    @Test
    void fenstermittelStimmtUndSchneidetAmAnfangAb() {
        final double[] prices = randomPrices(120);
        final HourlyGrid grid = new HourlyGrid(0L, HOUR, prices.clone());

        for (final int window : new int[]{1, 3, 24, 72, 500}) {
            for (int index = 0; index < prices.length; index++) {
                final int start = Math.max(0, index - window + 1);
                double sum = 0.0;
                for (int i = start; i <= index; i++) {
                    sum += prices[i];
                }
                assertThat(grid.meanOver(index, window))
                        .as("Mittel über %d bei %d", window, index)
                        .isCloseTo(sum / (index - start + 1), within(1e-12));
            }
        }
    }

    /**
     * Die Zeit seit der letzten Preisänderung zählt die unmittelbar vorangehenden gleichen Werte.
     */
    @Test
    void zeitSeitLetzterAenderungZaehltGleicheWerte() {
        final double[] prices = {1.50, 1.50, 1.50, 1.60, 1.60, 1.55};
        final HourlyGrid grid = new HourlyGrid(0L, HOUR, prices);

        assertThat(grid.minutesSinceChange(0)).isZero();
        assertThat(grid.minutesSinceChange(1)).isEqualTo(60.0);
        assertThat(grid.minutesSinceChange(2)).isEqualTo(120.0);
        assertThat(grid.minutesSinceChange(3)).isZero();
        assertThat(grid.minutesSinceChange(4)).isEqualTo(60.0);
        assertThat(grid.minutesSinceChange(5)).isZero();
    }

    /**
     * Der Verzögerungswert wird am Rasteranfang auf den ersten bekannten Preis abgeschnitten.
     */
    @Test
    void verzoegerungswertWirdAmAnfangAbgeschnitten() {
        final double[] prices = {1.50, 1.55, 1.60, 1.65};
        final HourlyGrid grid = new HourlyGrid(0L, HOUR, prices);

        assertThat(grid.laggedPrice(3, 1)).isEqualTo(1.60);
        assertThat(grid.laggedPrice(3, 3)).isEqualTo(1.50);
        assertThat(grid.laggedPrice(3, 99)).isEqualTo(1.50);
    }

    /**
     * Erzeugt reproduzierbare Preise mit Sprüngen und Haltephasen.
     *
     * @param count Anzahl der Rasterpunkte
     * @return Preise je Rasterpunkt
     */
    private double[] randomPrices(final int count) {
        final Random random = new Random(7);
        final double[] prices = new double[count];
        double price = 1.60;
        for (int index = 0; index < count; index++) {
            if (random.nextInt(4) == 0) {
                price = Math.round((price + (random.nextDouble() - 0.5) * 0.1) * 1000.0) / 1000.0;
            }
            prices[index] = price;
        }
        return prices;
    }

}
