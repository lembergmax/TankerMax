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

}
