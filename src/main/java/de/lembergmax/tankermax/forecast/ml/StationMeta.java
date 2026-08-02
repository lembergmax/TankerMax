package de.lembergmax.tankermax.forecast.ml;

/**
 * Stammdaten einer Tankstelle, soweit sie für die Vorhersage-Features benötigt werden.
 *
 * @param id     Kennung der Tankstelle
 * @param region Regionsschlüssel (zweistelliges Postleitzahl-Präfix) für die Regionalpreis-Features
 * @param state  Bundesland für die Feiertags-Features; kann {@code null} sein
 * @param brand  Markenname für das Marken-Niveau; leer, wenn keine Marke bekannt ist
 */
public record StationMeta(String id, String region, String state, String brand) {

}
