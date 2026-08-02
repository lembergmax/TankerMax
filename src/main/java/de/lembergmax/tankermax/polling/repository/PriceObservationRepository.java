package de.lembergmax.tankermax.polling.repository;

import de.lembergmax.tankermax.polling.domain.PriceObservation;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Datenbankzugriff auf {@link PriceObservation}-Entitäten.
 */
public interface PriceObservationRepository extends JpaRepository<PriceObservation, Long> {

}
