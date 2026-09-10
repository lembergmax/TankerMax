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
     * Wandelt Chart-Sekunden zurück in einen in UTC dargestellten Zeitstempel. Umkehrung von
     * {@link #fromUtc(LocalDateTime)}: Die Chart-Sekunden werden als lokale Wanduhrzeit
     * (Europe/Berlin) gelesen und in den zugehörigen UTC-Zeitpunkt überführt, damit das Frontend
     * eine im Chart abgelesene Zeitgrenze an die datenbankseitige Verlaufsabfrage übergeben kann.
     *
     * @param chartSeconds lokale Wanduhrzeit als UTC-Sekunden (Chart-Sekunden)
     * @return derselbe Zeitpunkt als naiver UTC-Zeitstempel
     */
    public LocalDateTime toUtc(final long chartSeconds) {
        final LocalDateTime berlinLocal = LocalDateTime.ofEpochSecond(chartSeconds, 0, ZoneOffset.UTC);
        final Instant instant = berlinLocal.atZone(BERLIN).toInstant();
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
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
