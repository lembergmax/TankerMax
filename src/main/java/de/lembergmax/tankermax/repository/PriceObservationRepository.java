package de.lembergmax.tankermax.repository;

import de.lembergmax.tankermax.domain.PriceObservation;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Datenbankzugriff auf {@link PriceObservation}-Entitäten.
 */
public interface PriceObservationRepository extends JpaRepository<PriceObservation, Long> {

}
