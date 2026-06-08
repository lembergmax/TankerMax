package de.lembergmax.tankermax.polling.service;

import de.lembergmax.tankermax.polling.client.dto.StationListItem;
import de.lembergmax.tankermax.polling.domain.FuelPrice;
import de.lembergmax.tankermax.polling.domain.ObservationStatus;
import de.lembergmax.tankermax.polling.domain.PriceObservation;
import de.lembergmax.tankermax.polling.domain.Station;
import de.lembergmax.tankermax.polling.repository.FuelPriceRepository;
import de.lembergmax.tankermax.polling.repository.FuelTypeRepository;
import de.lembergmax.tankermax.polling.repository.PriceObservationRepository;
import de.lembergmax.tankermax.polling.repository.StationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persistiert die Preisbeobachtung einer einzelnen Tankstelle samt der
 * zugehörigen Kraftstoffpreise.
 */
@Service
@Profile("ingest")
@RequiredArgsConstructor
public class PriceRecordingService {

    /** Technischer Schlüssel der Kraftstoffart Super E5. */
    private static final String FUEL_CODE_E5 = "E5";

    /** Technischer Schlüssel der Kraftstoffart Super E10. */
    private static final String FUEL_CODE_E10 = "E10";

    /** Technischer Schlüssel der Kraftstoffart Diesel. */
    private static final String FUEL_CODE_DIESEL = "DIESEL";

    /**
     * Obergrenze (ausschließlich) für einen plausiblen Literpreis in Euro. Reale Literpreise liegen
     * deutlich darunter; ein höherer Wert ist ein Ausreißer (etwa {@code 1899,000} statt
     * {@code 1,899}) und würde zudem den Wertebereich {@code DECIMAL(6,3)} der Preisspalte sprengen.
     */
    private static final BigDecimal MAX_PLAUSIBLE_PRICE = BigDecimal.TEN;

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
        final PriceObservation observation = createObservation(station, determineStatus(item), observedAt);
        persistFuelPrice(observation, FUEL_CODE_E5, item.getE5());
        persistFuelPrice(observation, FUEL_CODE_E10, item.getE10());
        persistFuelPrice(observation, FUEL_CODE_DIESEL, item.getDiesel());
    }

    /**
     * Bestimmt den Beobachtungsstatus aus dem Geöffnet-Kennzeichen und der Verfügbarkeit eines
     * plausiblen Preises.
     *
     * <p>Eine geschlossene Tankstelle ergibt {@link ObservationStatus#CLOSED}. Eine geöffnete
     * Tankstelle ohne jeglichen plausiblen Preis ergibt {@link ObservationStatus#UNAVAILABLE}, damit
     * sich eine reine Datenlücke der API später von einer Schließung unterscheiden lässt; andernfalls
     * {@link ObservationStatus#OPEN}.</p>
     *
     * @param item Tankstelle samt Preisen aus der Listen-Schnittstelle
     * @return abgeleiteter Beobachtungsstatus
     */
    private ObservationStatus determineStatus(final StationListItem item) {
        if (!item.isOpen()) {
            return ObservationStatus.CLOSED;
        }
        final boolean anyPrice = isPlausible(item.getE5()) || isPlausible(item.getE10()) || isPlausible(item.getDiesel());
        return anyPrice ? ObservationStatus.OPEN : ObservationStatus.UNAVAILABLE;
    }

    /**
     * Erzeugt und speichert die Preisbeobachtung einer Tankstelle.
     *
     * @param station    betroffene Tankstelle
     * @param status     Status der Tankstelle zum Abfragezeitpunkt
     * @param observedAt Zeitpunkt der Abfrage
     * @return die gespeicherte Preisbeobachtung
     */
    private PriceObservation createObservation(final Station station, final ObservationStatus status,
                                               final Instant observedAt) {
        final PriceObservation observation = new PriceObservation();
        observation.setStation(station);
        observation.setObservedAt(observedAt);
        observation.setStatus(status);
        return priceObservationRepository.save(observation);
    }

    /**
     * Speichert den Preis einer Kraftstoffart, sofern ein plausibler Preis vorliegt.
     *
     * <p>Ein unplausibler Wert (fehlend, kleiner oder gleich null oder oberhalb der Obergrenze) wird
     * übersprungen, statt gespeichert zu werden. So verfälschen Nullen und Ausreißer weder die
     * Auswertung, noch reißt ein den Wertebereich sprengender Betrag die gesamte pro-Tankstelle-
     * Transaktion samt Stammdaten in einen Rücklauf.</p>
     *
     * @param observation  zugehörige Preisbeobachtung
     * @param fuelTypeCode technischer Schlüssel der Kraftstoffart
     * @param amount       Preis je Liter oder {@code null}, falls nicht verfügbar
     */
    private void persistFuelPrice(final PriceObservation observation, final String fuelTypeCode, final BigDecimal amount) {
        if (!isPlausible(amount)) {
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

    /**
     * Prüft, ob ein Preis plausibel ist: vorhanden, größer als null und unterhalb der Obergrenze.
     *
     * @param amount zu prüfender Preis oder {@code null}
     * @return {@code true}, wenn der Preis in einem realistischen Bereich liegt
     */
    private static boolean isPlausible(final BigDecimal amount) {
        return amount != null
                && amount.signum() > 0
                && amount.compareTo(MAX_PLAUSIBLE_PRICE) < 0;
    }

}
