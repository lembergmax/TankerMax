package de.lembergmax.tankermax.forecast.ml;

/**
 * Roh-Preisbeobachtungen einer Tankstelle für eine Kraftstoffart, zeitlich aufsteigend sortiert.
 *
 * <p>Beide Felder sind gleich lang: {@code epochSeconds[i]} ist der Beobachtungszeitpunkt (Sekunden
 * seit der Epoche) und {@code prices[i]} der zugehörige Literpreis in Euro. Aus diesen
 * ereignisbasierten Beobachtungen baut der {@link PriceGridResampler} ein gleichmäßiges Zeitraster.</p>
 *
 * @param meta         Stammdaten der Tankstelle
 * @param epochSeconds aufsteigend sortierte Beobachtungszeitpunkte (Sekunden seit der Epoche)
 * @param prices       zugehörige Literpreise in Euro
 */
public record StationObservations(StationMeta meta, long[] epochSeconds, double[] prices) {

}
