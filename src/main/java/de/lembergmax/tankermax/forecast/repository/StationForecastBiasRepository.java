package de.lembergmax.tankermax.forecast.repository;

import de.lembergmax.tankermax.forecast.domain.StationForecastBias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Datenbankzugriff auf {@link StationForecastBias}-Entitäten.
 */
public interface StationForecastBiasRepository extends JpaRepository<StationForecastBias, Long> {

    /**
     * Liefert den Korrektureintrag einer Tankstelle, Kraftstoffart und eines Horizont-Abschnitts.
     *
     * @param stationId    Kennung der Tankstelle
     * @param fuelTypeId   Kennung der Kraftstoffart
     * @param horizonBucket Horizont-Abschnitt
     * @return vorhandener Korrektureintrag oder {@link Optional#empty()}
     */
    Optional<StationForecastBias> findByStation_IdAndFuelType_IdAndHorizonBucket(
            final String stationId, final Long fuelTypeId, final int horizonBucket);

    /**
     * Liefert alle Korrektureinträge einer Kraftstoffart, gebündelt zum Nachschlagen bei der Inferenz.
     *
     * @param fuelTypeId Kennung der Kraftstoffart
     * @return Korrektureinträge der Kraftstoffart
     */
    List<StationForecastBias> findByFuelType_Id(final Long fuelTypeId);

}
