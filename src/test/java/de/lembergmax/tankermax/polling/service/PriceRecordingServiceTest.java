package de.lembergmax.tankermax.polling.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.lembergmax.tankermax.polling.client.dto.StationListItem;
import de.lembergmax.tankermax.polling.domain.FuelPrice;
import de.lembergmax.tankermax.polling.domain.FuelType;
import de.lembergmax.tankermax.polling.domain.ObservationStatus;
import de.lembergmax.tankermax.polling.domain.PriceObservation;
import de.lembergmax.tankermax.polling.domain.Station;
import de.lembergmax.tankermax.polling.repository.FuelPriceRepository;
import de.lembergmax.tankermax.polling.repository.FuelTypeRepository;
import de.lembergmax.tankermax.polling.repository.PriceObservationRepository;
import de.lembergmax.tankermax.polling.repository.StationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit-Tests für {@link PriceRecordingService}: Plausibilitätsprüfung der Preise und Ableitung des
 * Beobachtungsstatus – mit gemockten Repositories, ohne Datenbank.
 */
class PriceRecordingServiceTest {

    /** Kennung der Test-Tankstelle. */
    private static final String STATION_ID = "stat-1";

    /** Gemocktes Tankstellen-Repository. */
    private final StationRepository stationRepository = mock(StationRepository.class);

    /** Gemocktes Kraftstoffart-Repository. */
    private final FuelTypeRepository fuelTypeRepository = mock(FuelTypeRepository.class);

    /** Gemocktes Beobachtungs-Repository. */
    private final PriceObservationRepository observationRepository = mock(PriceObservationRepository.class);

    /** Gemocktes Preis-Repository. */
    private final FuelPriceRepository fuelPriceRepository = mock(FuelPriceRepository.class);

    /** Prüfling. */
    private final PriceRecordingService service = new PriceRecordingService(
            stationRepository, fuelTypeRepository, observationRepository, fuelPriceRepository);

    /**
     * Bereitet die Standard-Mocks vor: Tankstelle vorhanden, Beobachtung wird unverändert
     * zurückgegeben, jede Kraftstoffart existiert.
     */
    private void givenStandardMocks() {
        final Station station = new Station();
        station.setId(STATION_ID);
        when(stationRepository.findById(STATION_ID)).thenReturn(Optional.of(station));
        when(observationRepository.save(any(PriceObservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fuelTypeRepository.findByCode(anyString())).thenReturn(Optional.of(new FuelType()));
    }

    /**
     * Erzeugt ein Listen-Item mit Status und Preisen.
     *
     * @param open   geöffnet-Kennzeichen
     * @param e5     E5-Preis oder {@code null}
     * @param e10    E10-Preis oder {@code null}
     * @param diesel Diesel-Preis oder {@code null}
     * @return befülltes Listen-Item
     */
    private StationListItem item(final boolean open, final BigDecimal e5, final BigDecimal e10, final BigDecimal diesel) {
        final StationListItem item = new StationListItem();
        item.setId(STATION_ID);
        item.setOpen(open);
        item.setE5(e5);
        item.setE10(e10);
        item.setDiesel(diesel);
        return item;
    }

    /**
     * Liest den Status der gespeicherten Beobachtung aus.
     *
     * @return abgeleiteter Beobachtungsstatus
     */
    private ObservationStatus capturedStatus() {
        final ArgumentCaptor<PriceObservation> captor = ArgumentCaptor.forClass(PriceObservation.class);
        verify(observationRepository).save(captor.capture());
        return captor.getValue().getStatus();
    }

    /**
     * Eine geöffnete Tankstelle mit gültigen Preisen ergibt Status OPEN und speichert die Preise.
     */
    @Test
    void geoeffnetMitPreisenErgibtOpenUndSpeichertPreise() {
        givenStandardMocks();
        service.recordFromListItem(item(true, new BigDecimal("1.759"), new BigDecimal("1.699"), new BigDecimal("1.659")),
                Instant.now());
        assertEquals(ObservationStatus.OPEN, capturedStatus());
        verify(fuelPriceRepository, times(3)).save(any(FuelPrice.class));
    }

    /**
     * Eine geschlossene Tankstelle ergibt Status CLOSED.
     */
    @Test
    void geschlossenErgibtClosed() {
        givenStandardMocks();
        service.recordFromListItem(item(false, new BigDecimal("1.759"), null, null), Instant.now());
        assertEquals(ObservationStatus.CLOSED, capturedStatus());
    }

    /**
     * Eine geöffnete Tankstelle ohne jeglichen plausiblen Preis ergibt Status UNAVAILABLE und
     * speichert keinen Preis.
     */
    @Test
    void geoeffnetOhnePreisErgibtUnavailable() {
        givenStandardMocks();
        service.recordFromListItem(item(true, null, null, null), Instant.now());
        assertEquals(ObservationStatus.UNAVAILABLE, capturedStatus());
        verify(fuelPriceRepository, never()).save(any(FuelPrice.class));
    }

    /**
     * Unplausible Preise (null, kleiner oder gleich null, Ausreißer) werden übersprungen; nur der
     * gültige Preis wird gespeichert.
     */
    @Test
    void unplausiblePreiseWerdenUebersprungen() {
        givenStandardMocks();
        service.recordFromListItem(item(true, new BigDecimal("0.000"), new BigDecimal("-0.500"), new BigDecimal("1899.000")),
                Instant.now());
        assertEquals(ObservationStatus.UNAVAILABLE, capturedStatus());
        verify(fuelPriceRepository, never()).save(any(FuelPrice.class));
    }

    /**
     * Eine fehlende Tankstelle führt zu keinem Schreibzugriff.
     */
    @Test
    void fehlendeTankstelleSchreibtNichts() {
        when(stationRepository.findById(STATION_ID)).thenReturn(Optional.empty());
        service.recordFromListItem(item(true, new BigDecimal("1.5"), null, null), Instant.now());
        verify(observationRepository, never()).save(any(PriceObservation.class));
        verify(fuelPriceRepository, never()).save(any(FuelPrice.class));
    }

}
