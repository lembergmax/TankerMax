package de.lembergmax.tankermax.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Tests für die Umrechnung in Chart-Sekunden in {@link ChartTime}.
 */
class ChartTimeTest {

    /**
     * Eine bereits lokale Zeit wird als ihre UTC-Sekunden kodiert (Identität der Wanduhrzeit).
     */
    @Test
    void lokaleZeitAlsUtcSekunden() {
        final LocalDateTime local = LocalDateTime.of(2026, 1, 15, 13, 0);
        assertEquals(local.toEpochSecond(ZoneOffset.UTC), ChartTime.fromLocal(local));
    }

    /**
     * Ein UTC-Zeitstempel wird auf die Berliner Wanduhrzeit verschoben: im Winter (UTC+1) wird aus
     * 12:00 UTC die lokale Wanduhrzeit 13:00, kodiert als deren UTC-Sekunden.
     */
    @Test
    void utcWirdAufBerlinerWanduhrVerschoben() {
        final LocalDateTime utc = LocalDateTime.of(2026, 1, 15, 12, 0);
        final LocalDateTime berlinLocal = LocalDateTime.of(2026, 1, 15, 13, 0);
        assertEquals(ChartTime.fromLocal(berlinLocal), ChartTime.fromUtc(utc));
    }

}
