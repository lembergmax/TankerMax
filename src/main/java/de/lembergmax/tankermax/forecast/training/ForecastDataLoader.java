package de.lembergmax.tankermax.forecast.training;

import de.lembergmax.tankermax.forecast.ml.StationMeta;
import de.lembergmax.tankermax.forecast.ml.StationObservations;
import de.lembergmax.tankermax.polling.domain.ObservationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lädt die historischen Preisbeobachtungen einer Kraftstoffart als Trainingsgrundlage.
 *
 * <p>Bewusst über {@link JdbcTemplate} und nicht über JPA umgesetzt: Für das Training werden große
 * Mengen reiner Zahlenwerte benötigt, die hier strömend in primitive Arrays gefüllt werden, ohne
 * Entitäten zu erzeugen. Das hält den Arbeitsspeicherbedarf auf dem Raspberry Pi gering. Nur im
 * Profil {@code ingest} aktiv.</p>
 */
@Service
@Profile("ingest")
@RequiredArgsConstructor
public class ForecastDataLoader {

    /** Statuswert einer geöffneten Beobachtung (aus dem Enum statt String-Literal in SQL). */
    private static final String OPEN_STATUS = ObservationStatus.OPEN.name();

    /** Anfangskapazität der je Tankstelle wachsenden Wertepuffer. */
    private static final int INITIAL_CAPACITY = 64;

    /** Zugriff auf die Datenbank. */
    private final JdbcTemplate jdbc;

    /**
     * Lädt die Beobachtungen einer Kraftstoffart ab dem Fensterbeginn, gruppiert je Tankstelle.
     *
     * @param fuelCode    Datenbank-Code des Kraftstoffs (zum Beispiel {@code E5})
     * @param windowStart früheste berücksichtigte Beobachtung (UTC)
     * @return Beobachtungen je Tankstelle mit angereicherten Stammdaten
     */
    public List<StationObservations> load(final String fuelCode, final LocalDateTime windowStart) {
        final Map<String, StationMeta> meta = loadStationMeta();
        final List<StationObservations> result = new ArrayList<>();
        final Accumulator accumulator = new Accumulator(meta, result);
        jdbc.query(
                "SELECT po.station_id AS station_id, po.observed_at AS observed_at, fp.amount AS amount "
                        + "FROM price_observation po "
                        + "JOIN fuel_price fp ON fp.observation_id = po.id "
                        + "JOIN fuel_type ft ON ft.id = fp.fuel_type_id "
                        + "WHERE ft.code = ? AND po.status = ? AND po.observed_at >= ? "
                        + "ORDER BY po.station_id, po.observed_at",
                resultSet -> {
                    final String stationId = resultSet.getString("station_id");
                    final LocalDateTime observedAt = resultSet.getObject("observed_at", LocalDateTime.class);
                    final double amount = resultSet.getBigDecimal("amount").doubleValue();
                    accumulator.add(stationId, observedAt.toEpochSecond(ZoneOffset.UTC), amount);
                },
                fuelCode, OPEN_STATUS, windowStart);
        accumulator.flush();
        return result;
    }

    /**
     * Lädt die für die Features benötigten Stammdaten aller Tankstellen.
     *
     * @return Stammdaten je Tankstellen-Kennung
     */
    private Map<String, StationMeta> loadStationMeta() {
        final Map<String, StationMeta> meta = new HashMap<>();
        jdbc.query(
                "SELECT s.id AS id, s.post_code AS post_code, s.state AS state, "
                        + "COALESCE(b.name, '') AS brand FROM station s LEFT JOIN brand b ON b.id = s.brand_id",
                resultSet -> {
                    final String id = resultSet.getString("id");
                    meta.put(id, new StationMeta(id, regionOf(resultSet.getString("post_code")),
                            resultSet.getString("state"), resultSet.getString("brand")));
                });
        return meta;
    }

    /**
     * Leitet den Regionsschlüssel aus den ersten beiden Stellen der Postleitzahl ab.
     *
     * @param postCode Postleitzahl oder {@code null}
     * @return zweistelliges Präfix oder ein Sammelschlüssel
     */
    private static String regionOf(final String postCode) {
        if (postCode == null || postCode.length() < 2) {
            return "??";
        }
        return postCode.substring(0, 2);
    }

    /**
     * Sammelt die strömenden Zeilen tankstellenweise und erzeugt beim Tankstellenwechsel die
     * {@link StationObservations}. Da die Abfrage nach Tankstelle sortiert ist, genügt ein einzelner
     * Puffer für die jeweils laufende Tankstelle.
     */
    private static final class Accumulator {

        /** Stammdaten je Tankstelle. */
        private final Map<String, StationMeta> meta;

        /** Zielliste der fertigen Beobachtungsreihen. */
        private final List<StationObservations> result;

        /** Kennung der laufenden Tankstelle; {@code null} vor der ersten Zeile. */
        private String currentId;

        /** Beobachtungszeitpunkte der laufenden Tankstelle. */
        private long[] times = new long[INITIAL_CAPACITY];

        /** Preise der laufenden Tankstelle. */
        private double[] prices = new double[INITIAL_CAPACITY];

        /** Anzahl der bislang gesammelten Beobachtungen der laufenden Tankstelle. */
        private int size;

        /**
         * Erzeugt den Sammler.
         *
         * @param meta   Stammdaten je Tankstelle
         * @param result Zielliste der fertigen Beobachtungsreihen
         */
        private Accumulator(final Map<String, StationMeta> meta, final List<StationObservations> result) {
            this.meta = meta;
            this.result = result;
        }

        /**
         * Fügt eine Beobachtung hinzu und schließt beim Tankstellenwechsel die vorige Reihe ab.
         *
         * @param stationId   Kennung der Tankstelle
         * @param epochSecond Beobachtungszeitpunkt (Sekunden seit der Epoche)
         * @param price       Literpreis
         */
        private void add(final String stationId, final long epochSecond, final double price) {
            if (currentId != null && !currentId.equals(stationId)) {
                flush();
            }
            currentId = stationId;
            if (size == times.length) {
                final int grown = times.length * 2;
                times = java.util.Arrays.copyOf(times, grown);
                prices = java.util.Arrays.copyOf(prices, grown);
            }
            times[size] = epochSecond;
            prices[size] = price;
            size++;
        }

        /**
         * Schließt die laufende Tankstelle ab und legt ihre Beobachtungsreihe in der Zielliste an,
         * sofern Stammdaten vorliegen.
         */
        private void flush() {
            if (currentId == null || size == 0) {
                reset();
                return;
            }
            final StationMeta stationMeta = meta.get(currentId);
            if (stationMeta != null) {
                result.add(new StationObservations(stationMeta,
                        java.util.Arrays.copyOf(times, size), java.util.Arrays.copyOf(prices, size)));
            }
            reset();
        }

        /**
         * Setzt den Puffer für die nächste Tankstelle zurück.
         */
        private void reset() {
            currentId = null;
            size = 0;
            if (times.length > INITIAL_CAPACITY) {
                times = new long[INITIAL_CAPACITY];
                prices = new double[INITIAL_CAPACITY];
            }
        }

    }

}
