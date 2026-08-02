package de.lembergmax.tankermax.polling.repository;

import de.lembergmax.tankermax.polling.domain.FuelType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Datenbankzugriff auf {@link FuelType}-Entitäten.
 */
public interface FuelTypeRepository extends JpaRepository<FuelType, Long> {

    /**
     * Sucht eine Kraftstoffart anhand ihres technischen Schlüssels.
     *
     * @param code technischer Schlüssel der Kraftstoffart (zum Beispiel {@code E5})
     * @return die gefundene Kraftstoffart oder ein leeres {@link Optional}
     */
    Optional<FuelType> findByCode(final String code);

}
