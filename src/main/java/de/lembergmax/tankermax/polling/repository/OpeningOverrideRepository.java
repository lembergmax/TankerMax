package de.lembergmax.tankermax.polling.repository;

import de.lembergmax.tankermax.polling.domain.OpeningOverride;
import de.lembergmax.tankermax.polling.domain.Station;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Datenbankzugriff auf {@link OpeningOverride}-Entitäten.
 */
public interface OpeningOverrideRepository extends JpaRepository<OpeningOverride, Long> {

    /**
     * Entfernt alle Ausnahmeregeln der angegebenen Tankstelle mit einer einzigen
     * Lösch-Anweisung, ohne die Zeilen zuvor zu laden.
     *
     * @param station Tankstelle, deren Ausnahmeregeln gelöscht werden
     */
    @Modifying
    @Query("DELETE FROM OpeningOverride oo WHERE oo.station = :station")
    void deleteByStation(@Param("station") final Station station);

}
