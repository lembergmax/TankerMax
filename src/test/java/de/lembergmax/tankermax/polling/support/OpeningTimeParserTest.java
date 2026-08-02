package de.lembergmax.tankermax.polling.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * Tests für das Parsen der Öffnungszeiten in {@link OpeningTimeParser}.
 */
class OpeningTimeParserTest {

    /**
     * Eine reguläre Uhrzeit wird korrekt geparst.
     */
    @Test
    void gueltigeZeit() {
        assertEquals(LocalTime.of(8, 30), OpeningTimeParser.parse("08:30:00"));
    }

    /**
     * Der API-Sonderwert {@code 24:00:00} wird auf das Tagesende abgebildet.
     */
    @Test
    void tagesendeSonderwert() {
        assertEquals(LocalTime.of(23, 59, 59), OpeningTimeParser.parse("24:00:00"));
    }

    /**
     * Leere oder fehlende Werte ergeben {@code null}.
     */
    @Test
    void leerOderNullErgibtNull() {
        assertNull(OpeningTimeParser.parse(null));
        assertNull(OpeningTimeParser.parse("   "));
    }

    /**
     * Nicht interpretierbare Werte ergeben {@code null}.
     */
    @Test
    void ungueltigErgibtNull() {
        assertNull(OpeningTimeParser.parse("keine-zeit"));
    }

}
