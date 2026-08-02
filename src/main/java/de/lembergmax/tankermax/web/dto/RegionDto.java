package de.lembergmax.tankermax.web.dto;

/**
 * Eine wählbare Region (konfigurierter Ort).
 *
 * @param id    technische Kennung der Region
 * @param label Anzeigename
 * @param count Anzahl der Tankstellen in der Region
 */
public record RegionDto(String id, String label, int count) {

}
