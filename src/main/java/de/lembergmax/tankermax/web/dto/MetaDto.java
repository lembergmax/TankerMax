package de.lembergmax.tankermax.web.dto;

/**
 * Allgemeine Metadaten für das Frontend.
 *
 * @param now             aktueller Zeitpunkt als Chart-Sekunden (für die Zeitachse)
 * @param tz              zugrunde liegende Zeitzone
 * @param forecastEnabled {@code true}, wenn die KI-Preisvorhersage aktiviert ist (steuert die
 *                        Sichtbarkeit der Vorhersage-Schaltfläche im Dashboard)
 */
public record MetaDto(long now, String tz, boolean forecastEnabled) {

}
