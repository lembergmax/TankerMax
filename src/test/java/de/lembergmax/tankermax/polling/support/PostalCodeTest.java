package de.lembergmax.tankermax.polling.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Tests für die Normalisierung deutscher Postleitzahlen in {@link PostalCode}.
 */
class PostalCodeTest {

    /**
     * Verlorene führende Nullen werden auf fünf Stellen wiederhergestellt.
     */
    @Test
    void fuehrendeNullenWiederhergestellt() {
        assertEquals("01067", PostalCode.normalize("1067"));
    }

    /**
     * Eine bereits fünfstellige Postleitzahl bleibt unverändert.
     */
    @Test
    void fuenfstelligBleibtUnveraendert() {
        assertEquals("10115", PostalCode.normalize("10115"));
    }

    /**
     * Leere oder fehlende Werte ergeben {@code null}.
     */
    @Test
    void leerOderNullErgibtNull() {
        assertNull(PostalCode.normalize(null));
        assertNull(PostalCode.normalize(" "));
    }

}
