package de.lembergmax.tankermax.polling.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.lembergmax.tankermax.polling.client.TankerkoenigApiClient;
import de.lembergmax.tankermax.polling.client.dto.StationDetailResponse;
import de.lembergmax.tankermax.polling.config.TankerkoenigProperties;
import de.lembergmax.tankermax.polling.domain.Station;
import de.lembergmax.tankermax.polling.repository.StationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * Unit-Tests für {@link StationDetailEnrichmentService}: Markierung bei leerer Antwort und Vermerk
 * eines Fehlversuchs samt Abbruch des Laufs bei einer Ausnahme – mit gemockten Kollaborateuren.
 */
class StationDetailEnrichmentServiceTest {

    /** Gemockter API-Client. */
    private final TankerkoenigApiClient apiClient = mock(TankerkoenigApiClient.class);

    /** Gemockter Stammdatendienst. */
    private final StationCatalogService catalogService = mock(StationCatalogService.class);

    /** Gemocktes Tankstellen-Repository. */
    private final StationRepository stationRepository = mock(StationRepository.class);

    /** Konfiguration mit Standardwerten. */
    private final TankerkoenigProperties properties = new TankerkoenigProperties();

    /** Prüfling. */
    private final StationDetailEnrichmentService service =
            new StationDetailEnrichmentService(apiClient, catalogService, stationRepository, properties);

    /**
     * Erzeugt eine Tankstelle mit der angegebenen Kennung.
     *
     * @param id Kennung der Tankstelle
     * @return Tankstelle mit gesetzter Kennung
     */
    private Station station(final String id) {
        final Station station = new Station();
        station.setId(id);
        return station;
    }

    /**
     * Eine gültige Antwort ohne Detaildaten markiert die Tankstelle als geprüft.
     */
    @Test
    void leereAntwortMarkiertAlsGeprueft() {
        when(stationRepository.countByDetailsFetchedAtIsNull()).thenReturn(1L, 0L);
        when(stationRepository.findByDetailsFetchedAtIsNull(any(Pageable.class)))
                .thenReturn(List.of(station("A")))
                .thenReturn(List.of());
        final StationDetailResponse emptyResponse = new StationDetailResponse();
        emptyResponse.setOk(false);
        when(apiClient.fetchStationDetail("A")).thenReturn(emptyResponse);

        service.enrichPendingStations();

        verify(catalogService).markDetailFetchAttempted("A");
        verify(catalogService, never()).applyDetail(any());
    }

    /**
     * Schlägt der Detailabruf mit einer Ausnahme fehl, wird der Fehlversuch vermerkt und der Lauf
     * abgebrochen, sodass die nachfolgende Tankstelle des Stapels nicht mehr abgefragt wird.
     */
    @Test
    void ausnahmeVermerktFehlversuchUndBrichtAb() {
        properties.getEnrichment().setMaxDetailFetchAttempts(3);
        when(stationRepository.countByDetailsFetchedAtIsNull()).thenReturn(2L);
        when(stationRepository.findByDetailsFetchedAtIsNull(any(Pageable.class)))
                .thenReturn(List.of(station("A"), station("B")));
        when(apiClient.fetchStationDetail("A")).thenThrow(new RuntimeException("timeout"));

        service.enrichPendingStations();

        verify(catalogService).recordDetailFetchFailure("A", 3);
        verify(apiClient, never()).fetchStationDetail("B");
        verify(catalogService, never()).applyDetail(any());
    }

}
