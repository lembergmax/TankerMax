package de.lembergmax.tankermax.repository;

import de.lembergmax.tankermax.domain.Station;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Datenbankzugriff auf {@link Station}-Entitäten.
 */
public interface StationRepository extends JpaRepository<Station, String> {

    /**
     * Liefert Tankstellen, die noch nicht um Detaildaten angereichert wurden.
     *
     * @param pageable begrenzt die Anzahl der zurückgegebenen Tankstellen
     * @return Tankstellen ohne Detaildaten
     */
    List<Station> findByDetailsFetchedAtIsNull(final Pageable pageable);

    /**
     * Zählt die Tankstellen, die noch nicht um Detaildaten angereichert wurden.
     *
     * @return Anzahl der Tankstellen ohne Detaildaten
     */
    long countByDetailsFetchedAtIsNull();

}
