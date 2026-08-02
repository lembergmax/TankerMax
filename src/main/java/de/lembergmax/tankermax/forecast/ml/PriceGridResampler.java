package de.lembergmax.tankermax.forecast.ml;

import lombok.experimental.UtilityClass;

/**
 * Legt die ereignisbasierten Preisbeobachtungen einer Tankstelle auf ein gleichmäßiges Zeitraster.
 *
 * <p>Tankstellen werden unregelmäßig abgefragt und ändern ihren Preis nur wenige Male am Tag. Für ein
 * Modell mit konstanten Abständen wird daher ein Raster aufgespannt und jeder Rasterpunkt mit dem
 * zuletzt bekannten Preis vorwärts gefüllt (Treppenfunktion). Rasterpunkte vor der ersten Beobachtung
 * erhalten den ersten bekannten Preis, damit keine Lücke am Anfang entsteht.</p>
 */
@UtilityClass
public class PriceGridResampler {

    /**
     * Baut das Zeitraster einer Tankstelle bis zum angegebenen Endzeitpunkt.
     *
     * @param observations sortierte Roh-Beobachtungen der Tankstelle (mindestens eine)
     * @param endEpochSecond letzter zu füllender Rasterzeitpunkt (Sekunden seit der Epoche)
     * @param stepSeconds    Rasterabstand in Sekunden
     * @return das gefüllte Zeitraster oder {@code null}, wenn keine Beobachtungen vorliegen
     */
    public HourlyGrid resample(final StationObservations observations, final long endEpochSecond,
                               final long stepSeconds) {
        final long[] epoch = observations.epochSeconds();
        final double[] price = observations.prices();
        if (epoch.length == 0) {
            return null;
        }
        final long base = floorToStep(epoch[0], stepSeconds);
        final long end = floorToStep(endEpochSecond, stepSeconds);
        if (end < base) {
            return null;
        }
        final int slots = (int) ((end - base) / stepSeconds) + 1;
        final double[] grid = new double[slots];
        int obsIndex = 0;
        double lastPrice = price[0];
        for (int slot = 0; slot < slots; slot++) {
            final long slotTime = base + (long) slot * stepSeconds;
            while (obsIndex < epoch.length && epoch[obsIndex] <= slotTime) {
                lastPrice = price[obsIndex];
                obsIndex++;
            }
            grid[slot] = lastPrice;
        }
        return new HourlyGrid(base, stepSeconds, grid);
    }

    /**
     * Rundet einen Zeitpunkt auf das nächstniedrigere Vielfache des Rasterabstands ab.
     *
     * @param epochSecond Zeitpunkt in Sekunden seit der Epoche
     * @param stepSeconds Rasterabstand in Sekunden
     * @return abgerundeter Rasterzeitpunkt
     */
    private long floorToStep(final long epochSecond, final long stepSeconds) {
        return Math.floorDiv(epochSecond, stepSeconds) * stepSeconds;
    }

}
