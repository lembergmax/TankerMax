package de.lembergmax.tankermax.forecast.repository;

import de.lembergmax.tankermax.forecast.domain.ForecastRun;
import de.lembergmax.tankermax.polling.domain.FuelType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Datenbankzugriff auf {@link ForecastRun}-Entitäten.
 */
public interface ForecastRunRepository extends JpaRepository<ForecastRun, Long> {

    /**
     * Liefert den jüngsten Vorhersagelauf einer Kraftstoffart.
     *
     * @param fuelType Kraftstoffart
     * @return jüngster Lauf oder {@link Optional#empty()}, wenn noch keiner existiert
     */
    Optional<ForecastRun> findTopByFuelTypeOrderByGeneratedAtDesc(final FuelType fuelType);

    /**
     * Zählt die Vorhersageläufe ab einem Zeitpunkt (zur Prüfung, ob heute bereits trainiert wurde).
     *
     * @param from frühester berücksichtigter Erzeugungszeitpunkt (einschließlich)
     * @return Anzahl der Läufe ab dem Zeitpunkt
     */
    long countByGeneratedAtGreaterThanEqual(final Instant from);

    /**
     * Löscht Vorhersageläufe, die vor dem Stichzeitpunkt erzeugt wurden. Die zugehörigen
     * Kurvenpunkte sind zuvor über {@link FuelPriceForecastRepository#deleteOlderThan(Instant)} zu
     * entfernen, da keine Datenbank-Kaskade besteht.
     *
     * @param cutoff Stichzeitpunkt; ältere Läufe werden gelöscht
     * @return Anzahl der gelöschten Läufe
     */
    @Modifying
    @Query("delete from ForecastRun r where r.generatedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") final Instant cutoff);

}
