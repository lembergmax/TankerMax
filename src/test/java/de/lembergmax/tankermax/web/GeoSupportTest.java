package de.lembergmax.tankermax.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests für die Haversine-Entfernungsberechnung in {@link GeoSupport}.
 */
class GeoSupportTest {

    /**
     * Zwei identische Punkte haben die Entfernung 0.
     */
    @Test
    void identischePunkteHabenAbstandNull() {
        assertEquals(0.0, GeoSupport.distanceKm(51.05, 13.74, 51.05, 13.74));
    }

    /**
     * Ein Grad Breitenunterschied entspricht rund 111 km.
     */
    @Test
    void einBreitengradEtwaHundertelfKilometer() {
        final double distance = GeoSupport.distanceKm(0.0, 0.0, 1.0, 0.0);
        assertTrue(distance > 111.0 && distance < 111.4, "erwartet ~111,2 km, war " + distance);
    }

    /**
     * Die Entfernung ist unabhängig von der Reihenfolge der Punkte.
     */
    @Test
    void entfernungIstSymmetrisch() {
        assertEquals(GeoSupport.distanceKm(51.0, 13.0, 52.0, 14.0),
                GeoSupport.distanceKm(52.0, 14.0, 51.0, 13.0));
    }

    /**
     * Das umschließende Rechteck enthält seinen Mittelpunkt.
     */
    @Test
    void boundingBoxEnthaeltMittelpunkt() {
        final GeoSupport.BoundingBox box = GeoSupport.boundingBox(51.05, 13.74, 25);
        assertTrue(box.latMin() < 51.05 && 51.05 < box.latMax(), "Mittelpunkt sollte in der Breite liegen");
        assertTrue(box.lngMin() < 13.74 && 13.74 < box.lngMax(), "Mittelpunkt sollte in der Länge liegen");
    }

    /**
     * Das Rechteck umschließt den Radius: Ein Punkt am Radius liegt innerhalb, ein deutlich weiter
     * entfernter außerhalb.
     */
    @Test
    void boundingBoxUmschliesstRadiusInBreite() {
        final double lat = 51.05;
        final double radiusKm = 25;
        final GeoSupport.BoundingBox box = GeoSupport.boundingBox(lat, 13.74, radiusKm);
        final double latAtRadius = lat + (radiusKm / 111.0) * 0.99;
        final double latBeyond = lat + (radiusKm / 111.0) * 1.5;
        assertTrue(latAtRadius <= box.latMax(), "Punkt am Radius sollte innerhalb der Box liegen");
        assertTrue(latBeyond > box.latMax(), "Punkt jenseits des Radius sollte außerhalb der Box liegen");
    }

}
