package de.lembergmax.tankermax.web.dto;

/**
 * Tanktipp des heutigen Tages für eine Tankstelle und Kraftstoffart.
 *
 * @param low            prognostiziertes Tagestief (€/L)
 * @param lowAt          Zeitpunkt des prognostizierten Tagestiefs als Chart-Sekunden (0, wenn unbekannt)
 * @param mean           prognostiziertes Tagesmittel (€/L)
 * @param current        Preis zum Ausgangszeitpunkt (€/L) oder {@code null}
 * @param recommendation Empfehlung ({@code TANKEN}, {@code WARTEN}, {@code NEUTRAL}) oder {@code null}
 * @param reason         Kurzbegründung der Empfehlung oder {@code null}
 * @param savingCt       erwartete Ersparnis beim Warten (Cent/Liter) oder {@code null}
 */
public record ForecastTipDto(double low, long lowAt, double mean, Double current,
                             String recommendation, String reason, Double savingCt) {

}
