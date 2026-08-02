package de.lembergmax.tankermax.polling.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.lembergmax.tankermax.polling.client.dto.StationDetail;
import de.lembergmax.tankermax.polling.client.dto.StationListItem;
import de.lembergmax.tankermax.polling.domain.Brand;
import de.lembergmax.tankermax.polling.domain.Station;
import de.lembergmax.tankermax.polling.repository.OpeningOverrideRepository;
import de.lembergmax.tankermax.polling.repository.OpeningTimeRepository;
import de.lembergmax.tankermax.polling.repository.StationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit-Tests für {@link StationCatalogService}: Wiederholung der Markenauflösung bei einer
 * Eindeutigkeitsverletzung, Hochzählen und Aufgeben des Fehlversuchszählers sowie das Ersetzen der
 * Öffnungszeiten beim Anreichern – mit gemockten Repositories.
 */
class StationCatalogServiceTest {

    /** Gemockter Markendienst. */
    private final BrandCatalogService brandCatalogService = mock(BrandCatalogService.class);

    /** Gemocktes Tankstellen-Repository. */
    private final StationRepository stationRepository = mock(StationRepository.class);

    /** Gemocktes Öffnungszeiten-Repository. */
    private final OpeningTimeRepository openingTimeRepository = mock(OpeningTimeRepository.class);

    /** Gemocktes Ausnahmeregel-Repository. */
    private final OpeningOverrideRepository openingOverrideRepository = mock(OpeningOverrideRepository.class);

    /** Prüfling. */
    private final StationCatalogService service = new StationCatalogService(
            brandCatalogService, stationRepository, openingTimeRepository, openingOverrideRepository);

    /**
     * Erzeugt eine Tankstelle mit Kennung und Fehlversuchszahl.
     *
     * @param id       Kennung der Tankstelle
     * @param failures bisherige Fehlversuche
     * @return Tankstelle mit gesetzten Werten
     */
    private Station station(final String id, final int failures) {
        final Station station = new Station();
        station.setId(id);
        station.setDetailFetchFailures(failures);
        return station;
    }

    /**
     * Eine Eindeutigkeitsverletzung beim Anlegen der Marke führt zu einem zweiten Auflösungsversuch,
     * dessen Ergebnis an der Tankstelle gesetzt wird.
     */
    @Test
    void markenkonfliktFuehrtZuZweitemAufloesungsversuch() {
        final StationListItem item = new StationListItem();
        item.setId("st");
        item.setBrand("Aral");
        item.setName("Aral Tankstelle");
        when(stationRepository.findById("st")).thenReturn(Optional.empty());
        final Brand brand = new Brand();
        brand.setName("Aral");
        when(brandCatalogService.getOrCreate("Aral"))
                .thenThrow(new DataIntegrityViolationException("doppelte Marke"))
                .thenReturn(brand);

        service.saveFromListItem(item);

        verify(brandCatalogService, times(2)).getOrCreate("Aral");
        final ArgumentCaptor<Station> captor = ArgumentCaptor.forClass(Station.class);
        verify(stationRepository).save(captor.capture());
        assertSame(brand, captor.getValue().getBrand());
    }

    /**
     * Ein Fehlversuch unterhalb der Höchstzahl erhöht nur den Zähler, ohne die Tankstelle aufzugeben.
     */
    @Test
    void fehlversuchUnterhalbDerSchwelleZaehltNurHoch() {
        final Station station = station("Y", 0);
        when(stationRepository.findById("Y")).thenReturn(Optional.of(station));

        service.recordDetailFetchFailure("Y", 3);

        assertEquals(1, station.getDetailFetchFailures());
        assertNull(station.getDetailsFetchedAt());
    }

    /**
     * Beim Erreichen der Höchstzahl an Fehlversuchen wird die Tankstelle als geprüft markiert.
     */
    @Test
    void fehlversuchAnDerSchwelleGibtTankstelleAuf() {
        final Station station = station("Z", 2);
        when(stationRepository.findById("Z")).thenReturn(Optional.of(station));

        service.recordDetailFetchFailure("Z", 3);

        assertEquals(3, station.getDetailFetchFailures());
        assertNotNull(station.getDetailsFetchedAt());
    }

    /**
     * Das Anreichern setzt den Fehlversuchszähler zurück und ersetzt die Öffnungszeiten (Löschen vor
     * Neuanlage).
     */
    @Test
    void anreichernSetztZaehlerZurueckUndErsetztOeffnungszeiten() {
        final Station station = station("d1", 2);
        when(stationRepository.findById("d1")).thenReturn(Optional.of(station));
        final StationDetail detail = new StationDetail();
        detail.setId("d1");
        detail.setState("Sachsen");
        detail.setWholeDay(true);
        detail.setOpeningTimes(List.of());
        detail.setOverrides(null);

        service.applyDetail(detail);

        assertEquals(0, station.getDetailFetchFailures());
        assertNotNull(station.getDetailsFetchedAt());
        verify(openingTimeRepository).deleteByStation(station);
        verify(openingOverrideRepository).deleteByStation(station);
    }

}
