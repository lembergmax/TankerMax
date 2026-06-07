package de.lembergmax.tankermax.repository;

import de.lembergmax.tankermax.domain.OpeningTime;
import de.lembergmax.tankermax.domain.Station;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Datenbankzugriff auf {@link OpeningTime}-Entitäten.
 */
public interface OpeningTimeRepository extends JpaRepository<OpeningTime, Long> {

    /**
     * Entfernt alle Öffnungszeiten der angegebenen Tankstelle mit einer einzigen
     * Lösch-Anweisung, ohne die Zeilen zuvor zu laden.
     *
     * @param station Tankstelle, deren Öffnungszeiten gelöscht werden
     */
    @Modifying
    @Query("DELETE FROM OpeningTime ot WHERE ot.station = :station")
    void deleteByStation(@Param("station") final Station station);

}
