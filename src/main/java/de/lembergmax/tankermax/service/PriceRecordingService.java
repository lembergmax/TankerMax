package de.lembergmax.tankermax.service;

import de.lembergmax.tankermax.client.dto.StationListItem;
import de.lembergmax.tankermax.domain.FuelPrice;
import de.lembergmax.tankermax.domain.ObservationStatus;
import de.lembergmax.tankermax.domain.PriceObservation;
import de.lembergmax.tankermax.domain.Station;
import de.lembergmax.tankermax.repository.FuelPriceRepository;
import de.lembergmax.tankermax.repository.FuelTypeRepository;
import de.lembergmax.tankermax.repository.PriceObservationRepository;
import de.lembergmax.tankermax.repository.StationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persistiert die Preisbeobachtung einer einzelnen Tankstelle samt der
 * zugehörigen Kraftstoffpreise.
 */
@Service
@RequiredArgsConstructor
public class PriceRecordingService {

    /** Technischer Schlüssel der Kraftstoffart Super E5. */
    private static final String FUEL_CODE_E5 = "E5";

    /** Technischer Schlüssel der Kraftstoffart Super E10. */
    private static final String FUEL_CODE_E10 = "E10";

    /** Technischer Schlüssel der Kraftstoffart Diesel. */
    private static final String FUEL_CODE_DIESEL = "DIESEL";

    /** Repository für Tankstellen. */
    private final StationRepository stationRepository;

    /** Repository für Kraftstoffarten. */
    private final FuelTypeRepository fuelTypeRepository;

    /** Repository für Preisbeobachtungen. */
    private final PriceObservationRepository priceObservationRepository;

    /** Repository für Kraftstoffpreise. */
    private final FuelPriceRepository fuelPriceRepository;

    /**
     * Speichert eine Preisbeobachtung aus den Listendaten einer Tankstelle.
     *
     * @param item       Tankstelle samt Preisen aus der Listen-Schnittstelle
     * @param observedAt Zeitpunkt der Abfrage
     */
    @Transactional
    public void recordFromListItem(final StationListItem item, final Instant observedAt) {
        final Station station = stationRepository.findById(item.getId()).orElse(null);
        if (station == null) {
            return;
        }
        final PriceObservation observation = createObservation(station, item.isOpen(), observedAt);
        persistFuelPrice(observation, FUEL_CODE_E5, item.getE5());
        persistFuelPrice(observation, FUEL_CODE_E10, item.getE10());
        persistFuelPrice(observation, FUEL_CODE_DIESEL, item.getDiesel());
    }

    /**
     * Erzeugt und speichert die Preisbeobachtung einer Tankstelle.
     *
     * @param station    betroffene Tankstelle
     * @param open       {@code true}, wenn die Tankstelle geöffnet ist
     * @param observedAt Zeitpunkt der Abfrage
     * @return die gespeicherte Preisbeobachtung
     */
    private PriceObservation createObservation(final Station station, final boolean open, final Instant observedAt) {
        final PriceObservation observation = new PriceObservation();
        observation.setStation(station);
        observation.setObservedAt(observedAt);
        observation.setStatus(ObservationStatus.fromOpenFlag(open));
        return priceObservationRepository.save(observation);
    }

    /**
     * Speichert den Preis einer Kraftstoffart, sofern ein Preis vorliegt.
     *
     * @param observation  zugehörige Preisbeobachtung
     * @param fuelTypeCode technischer Schlüssel der Kraftstoffart
     * @param amount       Preis je Liter oder {@code null}, falls nicht verfügbar
     */
    private void persistFuelPrice(final PriceObservation observation, final String fuelTypeCode, final BigDecimal amount) {
        if (amount == null) {
            return;
        }
        fuelTypeRepository.findByCode(fuelTypeCode).ifPresent(fuelType -> {
            final FuelPrice fuelPrice = new FuelPrice();
            fuelPrice.setObservation(observation);
            fuelPrice.setFuelType(fuelType);
            fuelPrice.setAmount(amount);
            fuelPriceRepository.save(fuelPrice);
        });
    }

}
