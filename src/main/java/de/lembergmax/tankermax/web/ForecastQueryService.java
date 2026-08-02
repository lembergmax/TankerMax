package de.lembergmax.tankermax.web;

import de.lembergmax.tankermax.web.dto.ForecastAccuracyDto;
import de.lembergmax.tankermax.web.dto.ForecastResponseDto;
import de.lembergmax.tankermax.web.dto.ForecastTipDto;
import de.lembergmax.tankermax.web.dto.PointDto;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Liest die vorab berechneten Preisvorhersagen aus der Datenbank und stellt sie dem Dashboard bereit.
 *
 * <p>Es findet kein Training und keine Modellauswertung statt; geliefert werden ausschließlich die im
 * Profil {@code ingest} erzeugten und gespeicherten Zeilen des jüngsten Vorhersagelaufs. Die
 * Zeitstempel werden – wie in der Ist-Historie – als „Chart-Sekunden" geliefert. Nur im Profil
 * {@code web} aktiv.</p>
 */
@Service
@Profile("web")
@RequiredArgsConstructor
public class ForecastQueryService {

    /** Zugriff auf die Datenbank. */
    private final JdbcTemplate jdbc;

    /**
     * Liefert die Vorhersage einer Tankstelle für einen Kraftstoff aus dem jüngsten Lauf.
     *
     * <p>Das Ergebnis wird je Tankstelle und Kraftstoff kurzzeitig zwischengespeichert, da es sich nur
     * einmal täglich ändert.</p>
     *
     * @param stationId Kennung der Tankstelle
     * @param fuelDb    Datenbank-Code des Kraftstoffs
     * @return Vorhersage samt Band, Tanktipp und Treffer-Statistik
     */
    @Cacheable("forecast")
    public ForecastResponseDto forecast(final String stationId, final String fuelDb) {
        final RunRef run = latestRun(fuelDb);
        if (run == null) {
            return empty();
        }
        final List<PointDto> points = new ArrayList<>();
        final List<PointDto> lower = new ArrayList<>();
        final List<PointDto> upper = new ArrayList<>();
        loadCurve(run.id(), stationId, points, lower, upper);
        if (points.isEmpty()) {
            return empty();
        }
        final ForecastTipDto tip = loadTip(stationId, run.fuelTypeId());
        final ForecastAccuracyDto accuracy = loadAccuracy(stationId, run.fuelTypeId());
        return new ForecastResponseDto(true, ChartTime.fromUtc(run.generatedAt()),
                points, lower, upper, tip, accuracy);
    }

    /**
     * Ermittelt den jüngsten Vorhersagelauf einer Kraftstoffart.
     *
     * @param fuelDb Datenbank-Code des Kraftstoffs
     * @return jüngster Lauf oder {@code null}, wenn keiner vorliegt
     */
    private RunRef latestRun(final String fuelDb) {
        return jdbc.query(
                "SELECT r.id AS id, r.fuel_type_id AS fuel_type_id, r.generated_at AS generated_at "
                        + "FROM forecast_run r JOIN fuel_type ft ON ft.id = r.fuel_type_id "
                        + "WHERE ft.code = ? ORDER BY r.generated_at DESC LIMIT 1",
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }
                    return new RunRef(resultSet.getLong("id"), resultSet.getLong("fuel_type_id"),
                            resultSet.getObject("generated_at", LocalDateTime.class));
                },
                fuelDb);
    }

    /**
     * Lädt die Kurvenpunkte eines Laufs für eine Tankstelle und füllt Kurve und Band.
     *
     * @param runId     Kennung des Laufs
     * @param stationId Kennung der Tankstelle
     * @param points    Zielliste der Punktvorhersage
     * @param lower     Zielliste des unteren Bandes
     * @param upper     Zielliste des oberen Bandes
     */
    private void loadCurve(final long runId, final String stationId, final List<PointDto> points,
                           final List<PointDto> lower, final List<PointDto> upper) {
        jdbc.query(
                "SELECT target_at, predicted_amount, predicted_low, predicted_high "
                        + "FROM fuel_price_forecast WHERE run_id = ? AND station_id = ? ORDER BY target_at",
                resultSet -> {
                    final long time = ChartTime.fromUtc(resultSet.getObject("target_at", LocalDateTime.class));
                    points.add(new PointDto(time, resultSet.getBigDecimal("predicted_amount").doubleValue()));
                    final java.math.BigDecimal low = resultSet.getBigDecimal("predicted_low");
                    final java.math.BigDecimal high = resultSet.getBigDecimal("predicted_high");
                    if (low != null && high != null) {
                        lower.add(new PointDto(time, low.doubleValue()));
                        upper.add(new PointDto(time, high.doubleValue()));
                    }
                },
                runId, stationId);
    }

    /**
     * Lädt den Tanktipp des heutigen Tages.
     *
     * @param stationId  Kennung der Tankstelle
     * @param fuelTypeId Kennung der Kraftstoffart
     * @return Tanktipp oder {@code null}, wenn für heute keiner vorliegt
     */
    private ForecastTipDto loadTip(final String stationId, final long fuelTypeId) {
        final LocalDate today = LocalDate.now(java.time.ZoneId.of(ChartTime.ZONE_ID));
        return jdbc.query(
                "SELECT predicted_low_amount, predicted_low_at, predicted_mean_amount, current_amount, "
                        + "recommendation, recommendation_reason, expected_saving_ct "
                        + "FROM forecast_daily_summary "
                        + "WHERE station_id = ? AND fuel_type_id = ? AND forecast_date = ?",
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }
                    final LocalDateTime lowAt = resultSet.getObject("predicted_low_at", LocalDateTime.class);
                    final java.math.BigDecimal current = resultSet.getBigDecimal("current_amount");
                    final Double saving = (Double) resultSet.getObject("expected_saving_ct");
                    return new ForecastTipDto(
                            resultSet.getBigDecimal("predicted_low_amount").doubleValue(),
                            lowAt == null ? 0 : ChartTime.fromUtc(lowAt),
                            resultSet.getBigDecimal("predicted_mean_amount").doubleValue(),
                            current == null ? null : current.doubleValue(),
                            resultSet.getString("recommendation"),
                            resultSet.getString("recommendation_reason"),
                            saving);
                },
                stationId, fuelTypeId, today);
    }

    /**
     * Lädt die Treffer-Statistik der bereits abgeglichenen Vorhersagen.
     *
     * @param stationId  Kennung der Tankstelle
     * @param fuelTypeId Kennung der Kraftstoffart
     * @return Treffer-Statistik oder {@code null}, wenn noch nichts abgeglichen wurde
     */
    private ForecastAccuracyDto loadAccuracy(final String stationId, final long fuelTypeId) {
        return jdbc.query(
                "SELECT COUNT(*) AS total, "
                        + "SUM(accuracy_class = 'RICHTIG') AS correct, "
                        + "SUM(accuracy_class = 'FAST') AS almost, "
                        + "SUM(accuracy_class = 'FALSCH') AS wrong, "
                        + "AVG(low_abs_error_ct) AS avg_error "
                        + "FROM forecast_daily_summary "
                        + "WHERE station_id = ? AND fuel_type_id = ? AND accuracy_class IS NOT NULL",
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }
                    final int total = resultSet.getInt("total");
                    if (total == 0) {
                        return null;
                    }
                    final java.math.BigDecimal avg = resultSet.getBigDecimal("avg_error");
                    return new ForecastAccuracyDto(total, resultSet.getInt("correct"),
                            resultSet.getInt("almost"), resultSet.getInt("wrong"),
                            avg == null ? null : avg.doubleValue());
                },
                stationId, fuelTypeId);
    }

    /**
     * Liefert eine leere Vorhersage-Antwort.
     *
     * @return Antwort ohne Vorhersagedaten
     */
    private ForecastResponseDto empty() {
        return new ForecastResponseDto(false, 0, List.of(), List.of(), List.of(), null, null);
    }

    /**
     * Verweis auf den jüngsten Vorhersagelauf.
     *
     * @param id          Kennung des Laufs
     * @param fuelTypeId  Kennung der Kraftstoffart
     * @param generatedAt Erzeugungszeitpunkt (UTC)
     */
    private record RunRef(long id, long fuelTypeId, LocalDateTime generatedAt) {

    }

}
