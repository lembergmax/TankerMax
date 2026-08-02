package de.lembergmax.tankermax.web.dto;

/**
 * Ein Punkt der Ist-Preiskurve.
 *
 * @param time  Zeit als Chart-Sekunden (lokale Wanduhr als UTC kodiert)
 * @param value Preis in Euro pro Liter
 */
public record PointDto(long time, double value) {

}
