package de.lembergmax.tankermax.polling.repository;

import de.lembergmax.tankermax.polling.domain.FuelPrice;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Datenbankzugriff auf {@link FuelPrice}-Entitäten.
 */
public interface FuelPriceRepository extends JpaRepository<FuelPrice, Long> {

}
