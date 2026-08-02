package de.lembergmax.tankermax.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests für die Umsetzung der Frontend-Kraftstoffkennungen in {@link FuelCodes}.
 */
class FuelCodesTest {

    /**
     * Die bekannten Kraftstoffe werden auf ihre Datenbank-Codes abgebildet.
     */
    @Test
    void bekannteKraftstoffe() {
        assertEquals("E5", FuelCodes.toDb("e5"));
        assertEquals("E10", FuelCodes.toDb("e10"));
        assertEquals("DIESEL", FuelCodes.toDb("diesel"));
    }

    /**
     * Die Umsetzung ist unabhängig von der Groß-/Kleinschreibung.
     */
    @Test
    void grossKleinUnabhaengig() {
        assertEquals("E5", FuelCodes.toDb("E5"));
        assertEquals("DIESEL", FuelCodes.toDb("Diesel"));
    }

    /**
     * {@code null} ergibt eine leere Zeichenkette.
     */
    @Test
    void nullErgibtLeer() {
        assertEquals("", FuelCodes.toDb(null));
    }

    /**
     * Unbekannte Werte werden in Großbuchstaben durchgereicht.
     */
    @Test
    void unbekanntWirdGrossgeschrieben() {
        assertEquals("SUPER", FuelCodes.toDb("super"));
    }

    /**
     * Die bekannten Kraftstoffe werden als bekannt erkannt – unabhängig von der Schreibweise.
     */
    @Test
    void bekannteKraftstoffeSindBekannt() {
        assertTrue(FuelCodes.isKnown("e5"));
        assertTrue(FuelCodes.isKnown("E10"));
        assertTrue(FuelCodes.isKnown("Diesel"));
    }

    /**
     * Unbekannte oder fehlende Werte werden als nicht bekannt eingestuft.
     */
    @Test
    void unbekannteKraftstoffeSindNichtBekannt() {
        assertFalse(FuelCodes.isKnown("super"));
        assertFalse(FuelCodes.isKnown(""));
        assertFalse(FuelCodes.isKnown(null));
    }

}
