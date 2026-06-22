package de.lembergmax.tankermax.forecast.training;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import de.lembergmax.tankermax.forecast.domain.StationForecastBias;
import de.lembergmax.tankermax.forecast.ml.ForecastContext;
import de.lembergmax.tankermax.forecast.ml.HourlyGrid;
import de.lembergmax.tankermax.forecast.repository.StationForecastBiasRepository;
import de.lembergmax.tankermax.polling.domain.FuelType;
import de.lembergmax.tankermax.polling.repository.FuelTypeRepository;
import de.lembergmax.tankermax.polling.repository.StationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Misst die systematische Abweichung früherer Vorhersagen und schreibt daraus die Selbstkorrektur
 * fort – so lernt die Vorhersage aus ihren eigenen Treffern.
 *
 * <p>Für das Gegenrechnen bei der Inferenz wird die zuletzt gemessene Abweichung je Tankstelle und
 * Horizont-Abschnitt geladen ({@link #loadBias(Long)}). Nach jedem Lauf wird sie neu bestimmt
 * ({@link #recompute(FuelType, ForecastContext, Instant)}): Die in der Datenbank gespeicherten
 * vergangenen Vorhersagepunkte werden mit dem tatsächlich eingetretenen Preis verglichen, der ohne
 * zusätzliche Abfrage aus dem bereits aufgebauten Zeitraster stammt. Der mediane Fehler je Abschnitt
 * wird gedeckelt und gespeichert. Nur im Profil {@code ingest} aktiv.</p>
 */
@Service
@Profile("ingest")
@RequiredArgsConstructor
public class ForecastFeedbackService {

    /** Anzahl der Horizont-Abschnitte (siehe {@link ForecastModelTrainer#bucketFor(int)}). */
    private static final int BUCKETS = 4;

    /** Mindestanzahl an Stichproben je Abschnitt, ab der eine Korrektur gespeichert wird. */
    private static final int MIN_SAMPLES = 10;

    /** Zugriff auf die Datenbank für das strömende Lesen der vergangenen Vorhersagepunkte. */
    private final JdbcTemplate jdbc;

    /** Repository der Selbstkorrektur-Einträge. */
    private final StationForecastBiasRepository biasRepository;

    /** Repository der Tankstellen (für Referenzen beim Speichern). */
    private final StationRepository stationRepository;

    /** Repository der Kraftstoffarten (für Referenzen beim Speichern). */
    private final FuelTypeRepository fuelTypeRepository;

    /**
     * Lädt die gespeicherte Selbstkorrektur einer Kraftstoffart, gebündelt je Tankstelle.
     *
     * @param fuelTypeId Kennung der Kraftstoffart
     * @return Abbildung Tankstellen-Kennung auf die Korrektur (Cent) je Horizont-Abschnitt
     */
    public Map<String, double[]> loadBias(final Long fuelTypeId) {
        final Map<String, double[]> byStation = new HashMap<>();
        for (final StationForecastBias bias : biasRepository.findByFuelType_Id(fuelTypeId)) {
            final double[] buckets = byStation.computeIfAbsent(bias.getStation().getId(), key -> new double[BUCKETS]);
            final int bucket = bias.getHorizonBucket();
            if (bucket >= 0 && bucket < BUCKETS) {
                buckets[bucket] = bias.getBiasCt();
            }
        }
        return byStation;
    }

    /**
     * Misst die Abweichung der vergangenen Vorhersagen gegen die tatsächlichen Preise neu und schreibt
     * die Selbstkorrektur fort.
     *
     * @param fuelType Kraftstoffart
     * @param ctx      Kontext mit dem aktuellen Zeitraster (liefert die tatsächlichen Preise)
     * @param now      Zeitpunkt des Laufs
     * @param props    Konfiguration (Zeitfenster und Deckel der Korrektur)
     */
    @Transactional
    public void recompute(final FuelType fuelType, final ForecastContext ctx, final Instant now,
                          final ForecastProperties props) {
        final LocalDateTime windowStart = LocalDateTime.ofInstant(
                now.minusSeconds((long) props.getFeedbackLookbackDays() * 86_400L), ZoneOffset.UTC);
        final LocalDateTime windowEnd = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        final Map<String, List<List<Double>>> errors = new HashMap<>();

        jdbc.query(
                "SELECT fpf.station_id AS station_id, fpf.target_at AS target_at, "
                        + "fpf.horizon_minutes AS horizon_minutes, fpf.predicted_amount AS predicted_amount "
                        + "FROM fuel_price_forecast fpf JOIN forecast_run r ON r.id = fpf.run_id "
                        + "WHERE r.fuel_type_id = ? AND fpf.target_at >= ? AND fpf.target_at <= ?",
                resultSet -> {
                    final String stationId = resultSet.getString("station_id");
                    final HourlyGrid grid = ctx.grid(stationId);
                    if (grid == null) {
                        return;
                    }
                    final long targetEpoch = resultSet.getObject("target_at", LocalDateTime.class)
                            .toEpochSecond(ZoneOffset.UTC);
                    if (!grid.coversTime(targetEpoch)) {
                        return;
                    }
                    final double predicted = resultSet.getBigDecimal("predicted_amount").doubleValue();
                    final double actual = grid.priceAtTime(targetEpoch);
                    final int bucket = ForecastModelTrainer.bucketFor(resultSet.getInt("horizon_minutes"));
                    errors.computeIfAbsent(stationId, key -> newBucketLists())
                            .get(bucket).add(predicted - actual);
                },
                fuelType.getId(), windowStart, windowEnd);

        persistBias(fuelType, errors, now, props.getFeedbackMaxCorrectionCt());
    }

    /**
     * Bestimmt je Tankstelle und Abschnitt den medianen Fehler und speichert ihn gedeckelt.
     *
     * @param fuelType      Kraftstoffart
     * @param errors        gesammelte Fehler je Tankstelle und Abschnitt (Euro/Liter)
     * @param now           Zeitpunkt des Laufs
     * @param maxCorrection Deckel der Korrektur in Cent/Liter
     */
    private void persistBias(final FuelType fuelType, final Map<String, List<List<Double>>> errors,
                             final Instant now, final double maxCorrection) {
        final FuelType fuelRef = fuelTypeRepository.getReferenceById(fuelType.getId());
        errors.forEach((stationId, buckets) -> {
            for (int bucket = 0; bucket < BUCKETS; bucket++) {
                final List<Double> samples = buckets.get(bucket);
                if (samples.size() < MIN_SAMPLES) {
                    continue;
                }
                final double biasCt = clampCorrection(median(samples) * 100.0, maxCorrection);
                final StationForecastBias entity = biasRepository
                        .findByStation_IdAndFuelType_IdAndHorizonBucket(stationId, fuelType.getId(), bucket)
                        .orElseGet(StationForecastBias::new);
                if (entity.getStation() == null) {
                    entity.setStation(stationRepository.getReferenceById(stationId));
                    entity.setFuelType(fuelRef);
                    entity.setHorizonBucket(bucket);
                }
                entity.setBiasCt(biasCt);
                entity.setSampleCount(samples.size());
                entity.setUpdatedAt(now);
                biasRepository.save(entity);
            }
        });
    }

    /**
     * Erzeugt eine leere Liste von Fehlerlisten, eine je Horizont-Abschnitt.
     *
     * @return Liste mit {@link #BUCKETS} leeren Fehlerlisten
     */
    private List<List<Double>> newBucketLists() {
        final List<List<Double>> lists = new ArrayList<>(BUCKETS);
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            lists.add(new ArrayList<>());
        }
        return lists;
    }

    /**
     * Berechnet den Median einer Fehlerliste.
     *
     * @param values Fehlerwerte
     * @return Median
     */
    private double median(final List<Double> values) {
        final List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        final int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    /**
     * Begrenzt die Korrektur betragsmäßig auf den Deckel.
     *
     * @param biasCt        gemessene Korrektur in Cent/Liter
     * @param maxCorrection Deckel in Cent/Liter
     * @return gedeckelte Korrektur
     */
    private double clampCorrection(final double biasCt, final double maxCorrection) {
        return Math.max(-maxCorrection, Math.min(maxCorrection, biasCt));
    }

}
