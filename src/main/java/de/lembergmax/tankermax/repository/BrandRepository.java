package de.lembergmax.tankermax.repository;

import de.lembergmax.tankermax.domain.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Datenbankzugriff auf {@link Brand}-Entitäten.
 */
public interface BrandRepository extends JpaRepository<Brand, Long> {

    /**
     * Sucht eine Marke anhand ihres Namens.
     *
     * @param name Name der gesuchten Marke
     * @return die gefundene Marke oder ein leeres {@link Optional}
     */
    Optional<Brand> findByName(final String name);

}
