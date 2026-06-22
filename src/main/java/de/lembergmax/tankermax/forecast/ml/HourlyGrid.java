package de.lembergmax.tankermax.forecast.ml;

/**
 * Gleichmäßiges Zeitraster der Preise einer Tankstelle.
 *
 * <p>Die ereignisbasierten Preisbeobachtungen werden auf ein festes Zeitraster (Standard: stündlich)
 * gelegt und vorwärts gefüllt, sodass jeder Rasterpunkt den zuletzt bekannten Preis trägt. Das
 * erlaubt es, Verzögerungs- und Fenster-Features leckfrei und mit konstanten Abständen zu berechnen.</p>
 */
public final class HourlyGrid {

    /** Zeitpunkt des ersten Rasterpunkts (Sekunden seit der Epoche). */
    private final long baseEpochSecond;

    /** Abstand zweier Rasterpunkte in Sekunden. */
    private final long stepSeconds;

    /** Vorwärts gefüllte Preise je Rasterpunkt. */
    private final double[] prices;

    /**
     * Erzeugt ein Zeitraster.
     *
     * @param baseEpochSecond Zeitpunkt des ersten Rasterpunkts (Sekunden seit der Epoche)
     * @param stepSeconds     Abstand zweier Rasterpunkte in Sekunden
     * @param prices          vorwärts gefüllte Preise je Rasterpunkt
     */
    public HourlyGrid(final long baseEpochSecond, final long stepSeconds, final double[] prices) {
        this.baseEpochSecond = baseEpochSecond;
        this.stepSeconds = stepSeconds;
        this.prices = prices;
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
     * Liefert den letzten (jüngsten) Rasterpreis.
     *
     * @return Preis am letzten Rasterpunkt
     */
    public double lastPrice() {
        return prices[prices.length - 1];
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

}
