package de.lembergmax.tankermax.repository;

import de.lembergmax.tankermax.domain.FuelPrice;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Datenbankzugriff auf {@link FuelPrice}-Entitäten.
 */
public interface FuelPriceRepository extends JpaRepository<FuelPrice, Long> {

}
