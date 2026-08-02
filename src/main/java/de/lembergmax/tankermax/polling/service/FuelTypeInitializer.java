package de.lembergmax.tankermax.polling.service;

import de.lembergmax.tankermax.polling.domain.FuelType;
import de.lembergmax.tankermax.polling.repository.FuelTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legt die unterstützten Kraftstoffarten beim Anwendungsstart an, sofern sie
 * noch nicht vorhanden sind.
 */
@Component
@Profile("ingest")
@RequiredArgsConstructor
public class FuelTypeInitializer implements ApplicationRunner {

    /** Repository für Kraftstoffarten. */
    private final FuelTypeRepository fuelTypeRepository;

    /**
     * Stellt sicher, dass die Kraftstoffarten Super E5, Super E10 und Diesel
     * existieren.
     *
     * @param args Argumente des Anwendungsstarts
     */
    @Override
    @Transactional
    public void run(final ApplicationArguments args) {
        ensureFuelType("E5", "Super E5");
        ensureFuelType("E10", "Super E10");
        ensureFuelType("DIESEL", "Diesel");
    }

    /**
     * Legt eine Kraftstoffart an, falls noch keine mit dem Schlüssel existiert.
     *
     * @param code  technischer Schlüssel der Kraftstoffart
     * @param label lesbare Bezeichnung der Kraftstoffart
     */
    private void ensureFuelType(final String code, final String label) {
        if (fuelTypeRepository.findByCode(code).isPresent()) {
            return;
        }
        final FuelType fuelType = new FuelType();
        fuelType.setCode(code);
        fuelType.setLabel(label);
        fuelTypeRepository.save(fuelType);
    }

}
