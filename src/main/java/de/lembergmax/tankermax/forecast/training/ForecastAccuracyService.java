package de.lembergmax.tankermax.forecast.training;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import de.lembergmax.tankermax.forecast.domain.AccuracyClass;
import de.lembergmax.tankermax.forecast.domain.ForecastDailySummary;
import de.lembergmax.tankermax.forecast.repository.ForecastDailySummaryRepository;
import de.lembergmax.tankermax.polling.domain.ObservationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Gleicht verstrichene Vorhersagetage mit den tatsächlich eingetretenen Preisen ab und ordnet die
 * Vorhersage einer Treffer-Klasse zu.
 *
 * <p>Für jede noch nicht abgeglichene Tageszusammenfassung, deren Tag vorüber ist, werden das
 * tatsächliche Tagestief und -mittel aus den Beobachtungen ermittelt und mit der Vorhersage
 * verglichen. Die Abweichung des Tagestiefs entscheidet über die Klasse richtig/fast/falsch. Lagen
 * für den Tag keine Preise vor, bleibt die Klasse offen, der Eintrag gilt aber als abgeglichen und
 * verlässt die Warteschlange. Nur im Profil {@code ingest} aktiv.</p>
 */
@Service
@Profile("ingest")
@RequiredArgsConstructor
public class ForecastAccuracyService {

    /** Zeitzone, in der die Vorhersagetage abgegrenzt werden. */
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Statuswert einer geöffneten Beobachtung. */
    private static final String OPEN_STATUS = ObservationStatus.OPEN.name();

    /** Cent-Faktor zur Umrechnung einer Euro-Abweichung. */
    private static final double CENT_FACTOR = 100.0;

    /** Zugriff auf die Datenbank für die tatsächlichen Preise. */
    private final JdbcTemplate jdbc;

    /** Repository der Tageszusammenfassungen. */
    private final ForecastDailySummaryRepository summaryRepository;

    /**
     * Gleicht alle fälligen, noch nicht abgeglichenen Tageszusammenfassungen ab.
     *
     * @param props Konfiguration mit den Treffer-Schwellen
     */
    @Transactional
    public void evaluatePending(final ForecastProperties props) {
        final LocalDate cutoff = LocalDate.now(BERLIN).minusDays(1);
        final List<ForecastDailySummary> pending =
                summaryRepository.findByEvaluatedAtIsNullAndForecastDateLessThanEqual(cutoff);
        final Instant now = Instant.now();
        for (final ForecastDailySummary summary : pending) {
            evaluate(summary, now, props);
        }
        summaryRepository.saveAll(pending);
    }

    /**
     * Gleicht eine einzelne Zusammenfassung ab und setzt die Treffer-Klasse.
     *
     * @param summary abzugleichende Zusammenfassung
     * @param now     Zeitpunkt des Abgleichs
     * @param props   Konfiguration mit den Treffer-Schwellen
     */
    private void evaluate(final ForecastDailySummary summary, final Instant now, final ForecastProperties props) {
        summary.setEvaluatedAt(now);
        final double[] actual = actualLowMean(summary.getStation().getId(),
                summary.getFuelType().getId(), summary.getForecastDate());
        if (actual == null || summary.getPredictedLowAmount() == null) {
            return;
        }
        summary.setActualLowAmount(round(actual[0]));
        summary.setActualMeanAmount(round(actual[1]));
        final double errorCt = Math.abs(summary.getPredictedLowAmount().doubleValue() - actual[0]) * CENT_FACTOR;
        summary.setLowAbsErrorCt(errorCt);
        summary.setAccuracyClass(classify(errorCt, props));
    }

    /**
     * Ermittelt das tatsächliche Tagestief und -mittel einer Tankstelle an einem Tag.
     *
     * @param stationId  Kennung der Tankstelle
     * @param fuelTypeId Kennung der Kraftstoffart
     * @param date       Vorhersagetag (lokal)
     * @return Array {Tagestief, Tagesmittel} oder {@code null}, wenn keine Preise vorlagen
     */
    private double[] actualLowMean(final String stationId, final Long fuelTypeId, final LocalDate date) {
        final LocalDateTime start = LocalDateTime.ofInstant(date.atStartOfDay(BERLIN).toInstant(), ZoneOffset.UTC);
        final LocalDateTime end = LocalDateTime.ofInstant(date.plusDays(1).atStartOfDay(BERLIN).toInstant(), ZoneOffset.UTC);
        return jdbc.query(
                "SELECT MIN(fp.amount) AS lo, AVG(fp.amount) AS mean FROM price_observation po "
                        + "JOIN fuel_price fp ON fp.observation_id = po.id "
                        + "WHERE po.station_id = ? AND fp.fuel_type_id = ? AND po.status = ? "
                        + "AND po.observed_at >= ? AND po.observed_at < ?",
                resultSet -> {
                    if (!resultSet.next() || resultSet.getBigDecimal("lo") == null) {
                        return null;
                    }
                    return new double[]{resultSet.getBigDecimal("lo").doubleValue(),
                            resultSet.getBigDecimal("mean").doubleValue()};
                },
                stationId, fuelTypeId, OPEN_STATUS, start, end);
    }

    /**
     * Ordnet eine Abweichung einer Treffer-Klasse zu.
     *
     * @param errorCt Abweichung in Cent/Liter
     * @param props   Konfiguration mit den Schwellen
     * @return Treffer-Klasse
     */
    private AccuracyClass classify(final double errorCt, final ForecastProperties props) {
        if (errorCt <= props.getAccuracyCorrectMaxCt()) {
            return AccuracyClass.RICHTIG;
        }
        if (errorCt <= props.getAccuracyAlmostMaxCt()) {
            return AccuracyClass.FAST;
        }
        return AccuracyClass.FALSCH;
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

}
