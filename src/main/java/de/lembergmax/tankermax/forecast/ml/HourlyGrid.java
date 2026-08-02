package de.lembergmax.tankermax.forecast.ml;

/**
 * Gleichmäßiges Zeitraster der Preise einer Tankstelle samt vorberechneter Fensterstatistiken.
 *
 * <p>Die ereignisbasierten Preisbeobachtungen werden auf ein festes Zeitraster (Standard: stündlich)
 * gelegt und vorwärts gefüllt, sodass jeder Rasterpunkt den zuletzt bekannten Preis trägt. Das
 * erlaubt es, Verzögerungs- und Fenster-Features leckfrei und mit konstanten Abständen zu berechnen.</p>
 *
 * <p>Da die Trainingsstichprobe jeden Rasterpunkt als Ausgangszeitpunkt nutzt, würde ein Scannen der
 * Fenster je Beispiel hunderte Millionen Array-Zugriffe kosten. Deshalb werden beim Aufbau einmalig
 * Präfixsummen (für gleitende Mittel), gleitende Minima und Maxima über das Tagesfenster sowie die
 * Zahl der Rasterschritte seit der letzten Preisänderung berechnet. Alle Abfragen sind danach in
 * konstanter Zeit möglich.</p>
 */
public final class HourlyGrid {

    /** Toleranz, ab der zwei Rasterpreise als verschieden gelten. */
    private static final double PRICE_EPSILON = 1e-6;

    /** Zeitpunkt des ersten Rasterpunkts (Sekunden seit der Epoche). */
    private final long baseEpochSecond;

    /** Abstand zweier Rasterpunkte in Sekunden. */
    private final long stepSeconds;

    /** Vorwärts gefüllte Preise je Rasterpunkt. */
    private final double[] prices;

    /** Präfixsummen der Preise; {@code prefix[i]} ist die Summe der ersten {@code i} Rasterpunkte. */
    private final double[] prefix;

    /** Kleinster Preis im zurückliegenden Tagesfenster je Rasterpunkt. */
    private final double[] dayMin;

    /** Größter Preis im zurückliegenden Tagesfenster je Rasterpunkt. */
    private final double[] dayMax;

    /** Rasterschritte seit der letzten Preisänderung je Rasterpunkt. */
    private final int[] stepsSinceChange;

    /**
     * Erzeugt ein Zeitraster und berechnet die abgeleiteten Fensterstatistiken.
     *
     * @param baseEpochSecond Zeitpunkt des ersten Rasterpunkts (Sekunden seit der Epoche)
     * @param stepSeconds     Abstand zweier Rasterpunkte in Sekunden
     * @param prices          vorwärts gefüllte Preise je Rasterpunkt
     */
    public HourlyGrid(final long baseEpochSecond, final long stepSeconds, final double[] prices) {
        this.baseEpochSecond = baseEpochSecond;
        this.stepSeconds = stepSeconds;
        this.prices = prices;
        this.prefix = buildPrefix(prices);
        final int dayWindow = Math.max(1, (int) Math.round(86_400.0 / stepSeconds));
        this.dayMin = slidingExtreme(prices, dayWindow, true);
        this.dayMax = slidingExtreme(prices, dayWindow, false);
        this.stepsSinceChange = buildStepsSinceChange(prices);
    }

    /**
     * Liefert die Anzahl der Rasterpunkte.
     *
     * @return Anzahl der Rasterpunkte
     */
    public int size() {
        return prices.length;
    }

    /**
     * Liefert den Preis am Rasterpunkt.
     *
     * @param index Rasterpunkt-Index (0-basiert)
     * @return Preis am Rasterpunkt
     */
    public double priceAt(final int index) {
        return prices[index];
    }

    /**
     * Liefert den Preis mit dem angegebenen Verzögerungsabstand, am Rasteranfang abgeschnitten.
     *
     * @param index    Ausgangsindex
     * @param lagSteps Verzögerung in Rasterschritten
     * @return verzögerter Preis
     */
    public double laggedPrice(final int index, final int lagSteps) {
        return prices[Math.max(0, index - lagSteps)];
    }

    /**
     * Liefert den letzten (jüngsten) Rasterpreis.
     *
     * @return Preis am letzten Rasterpunkt
     */
    public double lastPrice() {
        return prices[prices.length - 1];
    }

    /**
     * Liefert das Mittel über das zurückliegende Fenster bis zum Ausgangsindex (einschließlich), am
     * Rasteranfang abgeschnitten.
     *
     * @param index       Ausgangsindex (einschließlich)
     * @param windowSteps Fensterlänge in Rasterschritten
     * @return Mittelwert des Fensters
     */
    public double meanOver(final int index, final int windowSteps) {
        final int start = Math.max(0, index - windowSteps + 1);
        return (prefix[index + 1] - prefix[start]) / (index - start + 1);
    }

    /**
     * Liefert den kleinsten Preis im zurückliegenden Tagesfenster.
     *
     * @param index Ausgangsindex (einschließlich)
     * @return kleinster Preis des Tagesfensters
     */
    public double dayMin(final int index) {
        return dayMin[index];
    }

    /**
     * Liefert den größten Preis im zurückliegenden Tagesfenster.
     *
     * @param index Ausgangsindex (einschließlich)
     * @return größter Preis des Tagesfensters
     */
    public double dayMax(final int index) {
        return dayMax[index];
    }

    /**
     * Liefert die Minuten seit der letzten Preisänderung bis zum Ausgangsindex.
     *
     * @param index Ausgangsindex
     * @return Minuten seit der letzten Preisänderung
     */
    public double minutesSinceChange(final int index) {
        return (double) stepsSinceChange[index] * stepSeconds / 60.0;
    }

    /**
     * Liefert den Zeitpunkt eines Rasterpunkts.
     *
     * @param index Rasterpunkt-Index (0-basiert, darf den Rasterumfang übersteigen, etwa für
     *              zukünftige Zielzeitpunkte bei der Inferenz)
     * @return Zeitpunkt in Sekunden seit der Epoche
     */
    public long timeAt(final int index) {
        return baseEpochSecond + (long) index * stepSeconds;
    }

    /**
     * Liefert den Index des letzten Rasterpunkts.
     *
     * @return Index des letzten Rasterpunkts
     */
    public int lastIndex() {
        return prices.length - 1;
    }

    /**
     * Liefert den Rasterabstand in Sekunden.
     *
     * @return Rasterabstand in Sekunden
     */
    public long stepSeconds() {
        return stepSeconds;
    }

    /**
     * Prüft, ob der Zeitpunkt innerhalb des abgedeckten Rasterbereichs liegt.
     *
     * @param epochSecond Zeitpunkt in Sekunden seit der Epoche
     * @return {@code true}, wenn der Zeitpunkt im Raster liegt
     */
    public boolean coversTime(final long epochSecond) {
        return epochSecond >= baseEpochSecond && epochSecond <= timeAt(prices.length - 1);
    }

    /**
     * Liefert den Rasterpreis zum angegebenen Zeitpunkt (auf den nächsten Rasterpunkt abgeschnitten).
     *
     * @param epochSecond Zeitpunkt in Sekunden seit der Epoche
     * @return Preis am zugehörigen Rasterpunkt
     */
    public double priceAtTime(final long epochSecond) {
        final long offset = Math.floorDiv(epochSecond - baseEpochSecond, stepSeconds);
        final int index = (int) Math.min(prices.length - 1, Math.max(0, offset));
        return prices[index];
    }

    /**
     * Baut die Präfixsummen der Preise.
     *
     * @param values Preise je Rasterpunkt
     * @return Präfixsummen der Länge {@code values.length + 1}
     */
    private static double[] buildPrefix(final double[] values) {
        final double[] sums = new double[values.length + 1];
        for (int index = 0; index < values.length; index++) {
            sums[index + 1] = sums[index] + values[index];
        }
        return sums;
    }

    /**
     * Berechnet das gleitende Minimum bzw. Maximum über ein zurückliegendes Fenster in linearer Zeit.
     *
     * <p>Verwendet eine monotone Warteschlange: Sie hält nur die Indizes, die noch Extremwert werden
     * können, wodurch jeder Rasterpunkt genau einmal eingefügt und einmal entfernt wird.</p>
     *
     * @param values      Preise je Rasterpunkt
     * @param windowSteps Fensterlänge in Rasterschritten
     * @param minimum     {@code true} für das Minimum, {@code false} für das Maximum
     * @return Extremwert des Fensters je Rasterpunkt
     */
    private static double[] slidingExtreme(final double[] values, final int windowSteps, final boolean minimum) {
        final int count = values.length;
        final double[] out = new double[count];
        final int[] candidates = new int[count];
        int head = 0;
        int tail = 0;
        for (int index = 0; index < count; index++) {
            while (tail > head && dominates(values[candidates[tail - 1]], values[index], minimum)) {
                tail--;
            }
            candidates[tail++] = index;
            final int windowStart = index - windowSteps + 1;
            while (candidates[head] < windowStart) {
                head++;
            }
            out[index] = values[candidates[head]];
        }
        return out;
    }

    /**
     * Prüft, ob ein älterer Randwert durch einen neueren verdrängt wird.
     *
     * @param older   älterer Wert in der Warteschlange
     * @param newer   neu hinzukommender Wert
     * @param minimum {@code true} beim Minimum, {@code false} beim Maximum
     * @return {@code true}, wenn der ältere Wert nicht mehr Extremwert werden kann
     */
    private static boolean dominates(final double older, final double newer, final boolean minimum) {
        return minimum ? older >= newer : older <= newer;
    }

    /**
     * Bestimmt je Rasterpunkt die Zahl der Schritte, in denen der Preis bereits unverändert ist.
     *
     * @param values Preise je Rasterpunkt
     * @return Rasterschritte seit der letzten Preisänderung
     */
    private static int[] buildStepsSinceChange(final double[] values) {
        final int[] since = new int[values.length];
        for (int index = 1; index < values.length; index++) {
            since[index] = Math.abs(values[index] - values[index - 1]) > PRICE_EPSILON
                    ? 0
                    : since[index - 1] + 1;
        }
        return since;
    }

}
