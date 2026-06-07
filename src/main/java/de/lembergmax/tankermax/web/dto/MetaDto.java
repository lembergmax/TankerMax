package de.lembergmax.tankermax.web.dto;

/**
 * Allgemeine Metadaten für das Frontend.
 *
 * @param now aktueller Zeitpunkt als Chart-Sekunden (für die Zeitachse)
 * @param tz  zugrunde liegende Zeitzone
 */
public record MetaDto(long now, String tz) {

}
