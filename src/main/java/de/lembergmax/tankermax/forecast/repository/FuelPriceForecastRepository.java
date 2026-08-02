package de.lembergmax.tankermax.forecast.repository;

import de.lembergmax.tankermax.forecast.domain.FuelPriceForecast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Datenbankzugriff auf {@link FuelPriceForecast}-Entitäten.
 */
public interface FuelPriceForecastRepository extends JpaRepository<FuelPriceForecast, Long> {

    /**
     * Löscht alle Kurvenpunkte, deren Lauf vor dem Stichzeitpunkt erzeugt wurde. Wird vor dem
     * Aufräumen der Läufe ausgeführt, damit keine Fremdschlüssel verletzt werden.
     *
     * @param cutoff Stichzeitpunkt; Punkte älterer Läufe werden gelöscht
     * @return Anzahl der gelöschten Kurvenpunkte
     */
    @Modifying
    @Query("delete from FuelPriceForecast f where f.run in "
            + "(select r from de.lembergmax.tankermax.forecast.domain.ForecastRun r where r.generatedAt < :cutoff)")
    int deleteOlderThan(@Param("cutoff") final Instant cutoff);

}
