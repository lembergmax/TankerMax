package de.lembergmax.tankermax.forecast.repository;

import de.lembergmax.tankermax.forecast.domain.ForecastDailySummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Datenbankzugriff auf {@link ForecastDailySummary}-Entitäten.
 */
public interface ForecastDailySummaryRepository extends JpaRepository<ForecastDailySummary, Long> {

    /**
     * Liefert die Tageszusammenfassung einer Tankstelle, Kraftstoffart und eines Vorhersagetages,
     * damit ein erneuter Lauf den vorhandenen Eintrag aktualisiert statt zu duplizieren.
     *
     * @param stationId  Kennung der Tankstelle
     * @param fuelTypeId Kennung der Kraftstoffart
     * @param date       Vorhersagetag
     * @return vorhandene Zusammenfassung oder {@link Optional#empty()}
     */
    Optional<ForecastDailySummary> findByStation_IdAndFuelType_IdAndForecastDate(
            final String stationId, final Long fuelTypeId, final LocalDate date);

    /**
     * Liefert noch nicht abgeglichene Zusammenfassungen, deren Vorhersagetag bereits verstrichen ist.
     *
     * <p>Maßgeblich ist {@code evaluatedAt}: Ein einmal abgeglichener Eintrag verlässt die
     * Warteschlange auch dann, wenn für den Tag keine tatsächlichen Preise vorlagen und keine
     * Treffer-Klasse gesetzt werden konnte.</p>
     *
     * @param date jüngster bereits abgeschlossener Tag (einschließlich)
     * @return abzugleichende Zusammenfassungen
     */
    List<ForecastDailySummary> findByEvaluatedAtIsNullAndForecastDateLessThanEqual(final LocalDate date);

    /**
     * Löscht Zusammenfassungen, deren Vorhersagetag vor dem Stichtag liegt.
     *
     * @param cutoff Stichtag; ältere Zusammenfassungen werden gelöscht
     * @return Anzahl der gelöschten Zusammenfassungen
     */
    @Modifying
    @Query("delete from ForecastDailySummary s where s.forecastDate < :cutoff")
    int deleteOlderThan(@Param("cutoff") final LocalDate cutoff);

}
