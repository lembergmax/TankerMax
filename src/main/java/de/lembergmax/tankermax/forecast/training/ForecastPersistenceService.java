package de.lembergmax.tankermax.forecast.training;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import de.lembergmax.tankermax.forecast.domain.ForecastDailySummary;
import de.lembergmax.tankermax.forecast.domain.ForecastRun;
import de.lembergmax.tankermax.forecast.domain.FuelPriceForecast;
import de.lembergmax.tankermax.forecast.repository.ForecastDailySummaryRepository;
import de.lembergmax.tankermax.forecast.repository.ForecastRunRepository;
import de.lembergmax.tankermax.forecast.repository.FuelPriceForecastRepository;
import de.lembergmax.tankermax.forecast.training.ForecastModelTrainer.CurvePoint;
import de.lembergmax.tankermax.forecast.training.ForecastModelTrainer.DailyPoint;
import de.lembergmax.tankermax.forecast.training.ForecastModelTrainer.Result;
import de.lembergmax.tankermax.forecast.training.ForecastModelTrainer.StationForecast;
import de.lembergmax.tankermax.polling.domain.FuelType;
import de.lembergmax.tankermax.polling.domain.Station;
import de.lembergmax.tankermax.polling.repository.FuelTypeRepository;
import de.lembergmax.tankermax.polling.repository.StationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Speichert die berechnete Vorhersage in kurzen Transaktionen und räumt alte Daten auf.
 *
 * <p>Die rechenintensive Modellbildung läuft bewusst außerhalb einer Transaktion; hier werden nur die
 * fertigen Ergebnisse stapelweise persistiert, damit eine Datenbankverbindung nur kurz belegt wird.
 * Vorhandene Tageszusammenfassungen werden aktualisiert statt dupliziert. Nur im Profil
 * {@code ingest} aktiv.</p>
 */
@Service
@Profile("ingest")
@RequiredArgsConstructor
public class ForecastPersistenceService {

    /** Zeitzone, in der der Stichtag der Tageszusammenfassungen bestimmt wird. */
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Sekunden je Tag. */
    private static final long SECONDS_PER_DAY = 86_400L;

    /** Repository der Vorhersageläufe. */
    private final ForecastRunRepository runRepository;

    /** Repository der Kurvenpunkte. */
    private final FuelPriceForecastRepository forecastRepository;

    /** Repository der Tageszusammenfassungen. */
    private final ForecastDailySummaryRepository summaryRepository;

    /** Repository der Tankstellen (für Referenzen). */
    private final StationRepository stationRepository;

    /** Repository der Kraftstoffarten (für Referenzen). */
    private final FuelTypeRepository fuelTypeRepository;

    /**
     * Speichert das Vorhersage-Ergebnis einer Kraftstoffart (Lauf, Kurve und Tageszusammenfassungen).
     *
     * @param fuelTypeId Kennung der Kraftstoffart
     * @param result     berechnetes Ergebnis
     */
    @Transactional
    public void persist(final Long fuelTypeId, final Result result) {
        final FuelType fuelRef = fuelTypeRepository.getReferenceById(fuelTypeId);
        final ForecastRun run = new ForecastRun();
        run.setFuelType(fuelRef);
        run.setGeneratedAt(result.generatedAt());
        run.setOriginAt(result.originAt());
        run.setHorizonHours(result.horizonHours());
        run.setResolutionMinutes(result.resolutionMinutes());
        run.setModelVersion(result.modelVersion());
        run.setTrainRows(result.trainRows());
        run.setTrainMae(result.trainMae());
        runRepository.save(run);

        final List<FuelPriceForecast> curveBatch = new ArrayList<>();
        final List<ForecastDailySummary> summaryBatch = new ArrayList<>();
        for (final StationForecast stationForecast : result.stations()) {
            final Station stationRef = stationRepository.getReferenceById(stationForecast.stationId());
            for (final CurvePoint point : stationForecast.curve()) {
                curveBatch.add(toCurveEntity(run, stationRef, point));
            }
            for (final DailyPoint daily : stationForecast.dailies()) {
                summaryBatch.add(toSummaryEntity(fuelTypeId, fuelRef, stationRef, result, daily));
            }
        }
        forecastRepository.saveAll(curveBatch);
        summaryRepository.saveAll(summaryBatch);
    }

    /**
     * Entfernt veraltete Kurvenpunkte, Läufe und Tageszusammenfassungen gemäß den Aufbewahrungsfristen.
     *
     * @param now   Zeitpunkt des Laufs
     * @param props Konfiguration mit den Aufbewahrungsfristen
     */
    @Transactional
    public void prune(final Instant now, final ForecastProperties props) {
        final Instant curveCutoff = now.minusSeconds((long) props.getRetentionCurveDays() * SECONDS_PER_DAY);
        forecastRepository.deleteOlderThan(curveCutoff);
        runRepository.deleteOlderThan(curveCutoff);
        final LocalDate summaryCutoff = LocalDate.now(BERLIN).minusDays(props.getRetentionSummaryDays());
        summaryRepository.deleteOlderThan(summaryCutoff);
    }

    /**
     * Baut eine Kurvenpunkt-Entität aus einem berechneten Punkt.
     *
     * @param run        zugehöriger Lauf
     * @param stationRef Referenz auf die Tankstelle
     * @param point      berechneter Kurvenpunkt
     * @return Kurvenpunkt-Entität
     */
    private FuelPriceForecast toCurveEntity(final ForecastRun run, final Station stationRef, final CurvePoint point) {
        final FuelPriceForecast entity = new FuelPriceForecast();
        entity.setRun(run);
        entity.setStation(stationRef);
        entity.setTargetAt(point.targetAt());
        entity.setHorizonMinutes(point.horizonMinutes());
        entity.setPredictedAmount(round(point.predicted()));
        entity.setPredictedLow(roundNullable(point.low()));
        entity.setPredictedHigh(roundNullable(point.high()));
        return entity;
    }

    /**
     * Baut oder aktualisiert die Tageszusammenfassung aus einer berechneten Tageskennzahl.
     *
     * @param fuelTypeId Kennung der Kraftstoffart
     * @param fuelRef    Referenz auf die Kraftstoffart
     * @param stationRef Referenz auf die Tankstelle
     * @param result     Vorhersage-Ergebnis (für Metadaten)
     * @param daily      berechnete Tageskennzahl
     * @return zu speichernde Tageszusammenfassung
     */
    private ForecastDailySummary toSummaryEntity(final Long fuelTypeId, final FuelType fuelRef,
                                                 final Station stationRef, final Result result,
                                                 final DailyPoint daily) {
        final ForecastDailySummary summary = summaryRepository
                .findByStation_IdAndFuelType_IdAndForecastDate(stationRef.getId(), fuelTypeId, daily.date())
                .orElseGet(ForecastDailySummary::new);
        if (summary.getStation() == null) {
            summary.setStation(stationRef);
            summary.setFuelType(fuelRef);
            summary.setForecastDate(daily.date());
        }
        summary.setGeneratedAt(result.generatedAt());
        summary.setModelVersion(result.modelVersion());
        summary.setPredictedLowAmount(round(daily.low()));
        summary.setPredictedLowAt(daily.lowAt());
        summary.setPredictedMeanAmount(round(daily.mean()));
        summary.setPredictedLowQ10(roundNullable(daily.lowQ10()));
        summary.setPredictedLowQ90(roundNullable(daily.lowQ90()));
        summary.setRecommendation(daily.recommendation());
        summary.setRecommendationReason(daily.reason());
        summary.setExpectedSavingCt(daily.savingCt());
        summary.setCurrentAmount(roundNullable(daily.currentAmount()));
        return summary;
    }

    /**
     * Rundet einen Preis auf drei Nachkommastellen.
     *
     * @param value Preis
     * @return gerundeter Preis
     */
    private BigDecimal round(final double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * Rundet einen optionalen Preis auf drei Nachkommastellen.
     *
     * @param value Preis oder {@code null}
     * @return gerundeter Preis oder {@code null}
     */
    private BigDecimal roundNullable(final Double value) {
        return value == null ? null : round(value);
    }

}
