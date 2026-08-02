package de.lembergmax.tankermax.web;

import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Wandelt Zeitstempel in „Chart-Sekunden" um: die lokale Wanduhrzeit (Europe/Berlin)
 * als UTC-Sekunden kodiert, damit lightweight-charts sie als lokale Zeit darstellt.
 */
@UtilityClass
public class ChartTime {

    /** Kennung der Zeitzone, in der die Zeiten dem Nutzer angezeigt werden. */
    public final String ZONE_ID = "Europe/Berlin";

    /** Zeitzone, in der die Zeiten dem Nutzer angezeigt werden. */
    private final ZoneId BERLIN = ZoneId.of(ZONE_ID);

    /**
     * Wandelt einen in UTC gespeicherten Zeitstempel in Chart-Sekunden um.
     *
     * @param utc naiver Zeitstempel, der UTC darstellt
     * @return lokale Wanduhrzeit als UTC-Sekunden
     */
    public long fromUtc(final LocalDateTime utc) {
        final Instant instant = utc.toInstant(ZoneOffset.UTC);
        return instant.atZone(BERLIN).toLocalDateTime().toEpochSecond(ZoneOffset.UTC);
    }

    /**
     * Wandelt eine bereits lokale (naive) Zeit in Chart-Sekunden um.
     *
     * @param local naive lokale Zeit (Europe/Berlin)
     * @return lokale Wanduhrzeit als UTC-Sekunden
     */
    public long fromLocal(final LocalDateTime local) {
        return local.toEpochSecond(ZoneOffset.UTC);
    }

    /**
     * Liefert den aktuellen Zeitpunkt als Chart-Sekunden.
     *
     * @return aktuelle lokale Wanduhrzeit als UTC-Sekunden
     */
    public long now() {
        return LocalDateTime.now(BERLIN).toEpochSecond(ZoneOffset.UTC);
    }

}
