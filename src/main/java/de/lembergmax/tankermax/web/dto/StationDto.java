package de.lembergmax.tankermax.web.dto;

import java.util.List;

/**
 * Eine Tankstelle mit aktuellem Preis für die Liste/Detailansicht.
 *
 * @param id           Kennung der Tankstelle
 * @param brand        Marke
 * @param name         vollständiger Name
 * @param street       Straße mit Hausnummer
 * @param postCode     Postleitzahl
 * @param place        Ort
 * @param lat          geografische Breite
 * @param lng          geografische Länge
 * @param dist         Entfernung zum Ortszentrum in Kilometern
 * @param isOpen       {@code true}, wenn ein aktueller Preis vorliegt (geöffnet)
 * @param wholeDay     {@code true}, wenn rund um die Uhr geöffnet
 * @param priceNow     aktueller Preis in Euro pro Liter oder {@code null}
 * @param openingTimes reguläre Öffnungszeiten; leer, solange die Tankstelle nicht angereichert ist
 */
public record StationDto(
        String id,
        String brand,
        String name,
        String street,
        String postCode,
        String place,
        double lat,
        double lng,
        double dist,
        boolean isOpen,
        boolean wholeDay,
        Double priceNow,
        List<OpeningTimeDto> openingTimes) {

}
