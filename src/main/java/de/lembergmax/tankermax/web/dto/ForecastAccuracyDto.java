package de.lembergmax.tankermax.web.dto;

/**
 * Kompakte Treffer-Statistik der jüngsten, bereits abgeglichenen Vorhersagen einer Tankstelle und
 * Kraftstoffart.
 *
 * @param total      Anzahl der ausgewerteten Vorhersagetage
 * @param correct    Anzahl der als „richtig" eingestuften Tage
 * @param almost     Anzahl der als „fast richtig" eingestuften Tage
 * @param wrong      Anzahl der als „falsch" eingestuften Tage
 * @param avgErrorCt mittlere Abweichung des Tagestiefs (Cent/Liter) oder {@code null}
 */
public record ForecastAccuracyDto(int total, int correct, int almost, int wrong, Double avgErrorCt) {

}
