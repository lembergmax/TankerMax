package de.lembergmax.tankermax.polling.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.lembergmax.tankermax.polling.client.TankerkoenigApiClient;
import de.lembergmax.tankermax.polling.client.dto.StationListItem;
import de.lembergmax.tankermax.polling.client.dto.StationListResponse;
import de.lembergmax.tankermax.polling.config.Location;
import de.lembergmax.tankermax.polling.config.TankerkoenigProperties;
import de.lembergmax.tankermax.polling.repository.StationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * Unit-Tests für {@link LocationPricePollService}: Ortsrotation, Stillstandserkennung und
 * Entdopplung der Antwort – mit gemockten Kollaborateuren und ohne Datenbank.
 */
class LocationPricePollServiceTest {

    /** Gemockter API-Client. */
    private final TankerkoenigApiClient apiClient = mock(TankerkoenigApiClient.class);

    /** Gemockter Stammdatendienst. */
    private final StationCatalogService catalogService = mock(StationCatalogService.class);

    /** Gemockter Preisdienst. */
    private final PriceRecordingService priceRecordingService = mock(PriceRecordingService.class);

    /** Gemocktes Tankstellen-Repository. */
    private final StationRepository stationRepository = mock(StationRepository.class);

    /** Gemockte Transaktionsverwaltung, deren Callback synchron ausgeführt wird. */
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    /** Registry für die Kennzahlen. */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /**
     * Erzeugt den Prüfling mit den angegebenen Orten und der Stillstandsschwelle.
     *
     * @param maxStallCycles Höchstzahl der Pausenzyklen ohne Fortschritt
     * @param locationNames  Namen der konfigurierten Orte
     * @return einsatzbereiter Dienst
     */
    private LocationPricePollService newService(final int maxStallCycles, final String... locationNames) {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        final TankerkoenigProperties properties = new TankerkoenigProperties();
        properties.getPoll().setMaxStallCycles(maxStallCycles);
        final List<Location> locations = new ArrayList<>();
        for (final String name : locationNames) {
            final Location location = new Location();
            location.setName(name);
            locations.add(location);
        }
        properties.setLocations(locations);
        return new LocationPricePollService(apiClient, catalogService, priceRecordingService,
                stationRepository, properties, transactionManager, meterRegistry);
    }

    /**
     * Erzeugt eine erfolgreiche Antwort mit Tankstellen zu den angegebenen Kennungen.
     *
     * @param ids Kennungen der enthaltenen Tankstellen
     * @return befüllte, als erfolgreich markierte Antwort
     */
    private StationListResponse responseWith(final String... ids) {
        final StationListResponse response = new StationListResponse();
        response.setOk(true);
        final List<StationListItem> items = new ArrayList<>();
        for (final String id : ids) {
            final StationListItem item = new StationListItem();
            item.setId(id);
            items.add(item);
        }
        response.setStations(items);
        return response;
    }

    /**
     * Bei freier Anreicherung werden die Orte der Reihe nach rotierend abgefragt.
     */
    @Test
    void fragtOrteRotierendDerReiheNachAb() {
        final LocationPricePollService service = newService(5, "A", "B", "C");
        when(stationRepository.countByDetailsFetchedAtIsNull()).thenReturn(0L);
        when(apiClient.fetchStations(any(Location.class))).thenReturn(responseWith("s1"));

        service.pollNextLocation();
        service.pollNextLocation();
        service.pollNextLocation();

        final ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
        verify(apiClient, times(3)).fetchStations(captor.capture());
        assertEquals(List.of("A", "B", "C"), captor.getAllValues().stream().map(Location::getName).toList());
        assertNotNull(service.getLastSuccessfulPollAt());
    }

    /**
     * Solange die Anreicherung nicht vorankommt, pausiert die Preisabfrage, bis die Stillstandsschwelle
     * erreicht ist; danach wird sie erzwungen.
     */
    @Test
    void erzwingtAbfrageErstNachStillstandsschwelle() {
        final LocationPricePollService service = newService(2, "A");
        when(stationRepository.countByDetailsFetchedAtIsNull()).thenReturn(5L);
        when(apiClient.fetchStations(any(Location.class))).thenReturn(responseWith("s1"));

        service.pollNextLocation();
        service.pollNextLocation();
        verify(apiClient, times(0)).fetchStations(any(Location.class));
        assertNull(service.getLastSuccessfulPollAt());

        service.pollNextLocation();
        verify(apiClient, times(1)).fetchStations(any(Location.class));
        assertEquals(1.0, meterRegistry.counter("tankermax.enrichment.stalls").count());
    }

    /**
     * Mehrfach in einer Antwort enthaltene Tankstellen werden vor der Verarbeitung entdoppelt.
     */
    @Test
    void entdoppeltTankstellenEinerAntwort() {
        final LocationPricePollService service = newService(5, "A");
        when(stationRepository.countByDetailsFetchedAtIsNull()).thenReturn(0L);
        when(apiClient.fetchStations(any(Location.class))).thenReturn(responseWith("s1", "s1", "s2"));

        service.pollNextLocation();

        verify(priceRecordingService, times(2)).recordFromListItem(any(StationListItem.class), any());
        verify(catalogService, times(2)).saveFromListItem(any(StationListItem.class));
        assertEquals(2.0, meterRegistry.counter("tankermax.stations.recorded").count());
    }

}
