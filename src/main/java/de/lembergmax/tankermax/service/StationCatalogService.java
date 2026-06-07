package de.lembergmax.tankermax.service;

import de.lembergmax.tankermax.client.dto.OpeningTimeDto;
import de.lembergmax.tankermax.client.dto.StationDetail;
import de.lembergmax.tankermax.client.dto.StationListItem;
import de.lembergmax.tankermax.domain.Brand;
import de.lembergmax.tankermax.domain.OpeningOverride;
import de.lembergmax.tankermax.domain.OpeningTime;
import de.lembergmax.tankermax.domain.Station;
import de.lembergmax.tankermax.repository.BrandRepository;
import de.lembergmax.tankermax.repository.OpeningOverrideRepository;
import de.lembergmax.tankermax.repository.OpeningTimeRepository;
import de.lembergmax.tankermax.repository.StationRepository;
import de.lembergmax.tankermax.support.OpeningTimeParser;
import de.lembergmax.tankermax.support.PostalCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Speichert und aktualisiert die Stammdaten von Tankstellen, Marken,
 * Öffnungszeiten und Ausnahmeregeln.
 */
@Service
@RequiredArgsConstructor
public class StationCatalogService {

    /** Repository für Marken. */
    private final BrandRepository brandRepository;

    /** Repository für Tankstellen. */
    private final StationRepository stationRepository;

    /** Repository für Öffnungszeiten. */
    private final OpeningTimeRepository openingTimeRepository;

    /** Repository für Ausnahmeregeln. */
    private final OpeningOverrideRepository openingOverrideRepository;

    /**
     * Legt eine Tankstelle aus den Listendaten an oder aktualisiert sie.
     *
     * @param item Tankstelle aus der Listen-Schnittstelle
     */
    @Transactional
    public void saveFromListItem(final StationListItem item) {
        final Station station = stationRepository.findById(item.getId())
                .orElseGet(() -> createStation(item.getId()));
        station.setBrand(resolveBrand(item.getBrand()));
        station.setName(item.getName());
        station.setStreet(item.getStreet());
        station.setHouseNumber(item.getHouseNumber());
        station.setPostCode(PostalCode.normalize(item.getPostCode()));
        station.setPlace(item.getPlace());
        station.setLatitude(item.getLat());
        station.setLongitude(item.getLng());
        station.setLastUpdatedAt(Instant.now());
        stationRepository.save(station);
    }

    /**
     * Reichert eine bereits gespeicherte Tankstelle um Detaildaten an.
     *
     * @param detail Detaildaten aus der Detail-Schnittstelle
     */
    @Transactional
    public void applyDetail(final StationDetail detail) {
        stationRepository.findById(detail.getId()).ifPresent(station -> {
            station.setState(detail.getState());
            station.setWholeDay(detail.isWholeDay());
            station.setLastUpdatedAt(Instant.now());
            station.setDetailsFetchedAt(Instant.now());
            stationRepository.save(station);
            replaceOpeningTimes(station, detail.getOpeningTimes());
            replaceOverrides(station, detail.getOverrides());
        });
    }

    /**
     * Markiert eine Tankstelle als auf Detaildaten geprüft, ohne Detaildaten zu
     * übernehmen. So fällt eine endgültig nicht abrufbare Tankstelle aus der
     * Anreicherungs-Warteschlange und blockiert die Preisabfrage nicht dauerhaft.
     *
     * @param stationId Kennung der Tankstelle
     */
    @Transactional
    public void markDetailFetchAttempted(final String stationId) {
        stationRepository.findById(stationId).ifPresent(station -> {
            station.setDetailsFetchedAt(Instant.now());
            stationRepository.save(station);
        });
    }

    /**
     * Erzeugt eine neue Tankstelle mit gesetztem Erstimportzeitpunkt.
     *
     * @param id Kennung der Tankstelle
     * @return die neue, noch nicht gespeicherte Tankstelle
     */
    private Station createStation(final String id) {
        final Station station = new Station();
        station.setId(id);
        station.setFirstImportedAt(Instant.now());
        return station;
    }

    /**
     * Ermittelt die Marke zum Namen oder legt sie an.
     *
     * @param brandName Name der Marke; leer oder {@code null} ergibt keine Marke
     * @return die zugehörige Marke oder {@code null}
     */
    private Brand resolveBrand(final String brandName) {
        if (brandName == null || brandName.isBlank()) {
            return null;
        }
        return brandRepository.findByName(brandName)
                .orElseGet(() -> createBrand(brandName));
    }

    /**
     * Legt eine neue Marke an.
     *
     * @param brandName Name der Marke
     * @return die gespeicherte Marke
     */
    private Brand createBrand(final String brandName) {
        final Brand brand = new Brand();
        brand.setName(brandName);
        return brandRepository.save(brand);
    }

    /**
     * Ersetzt alle Öffnungszeiten einer Tankstelle durch die gelieferten.
     *
     * @param station      betroffene Tankstelle
     * @param openingTimes neue Öffnungszeiten oder {@code null}
     */
    private void replaceOpeningTimes(final Station station, final List<OpeningTimeDto> openingTimes) {
        openingTimeRepository.deleteByStation(station);
        if (openingTimes == null) {
            return;
        }
        openingTimes.forEach(dto -> openingTimeRepository.save(toOpeningTime(station, dto)));
    }

    /**
     * Bildet ein Öffnungszeit-DTO auf eine Entität ab.
     *
     * @param station zugehörige Tankstelle
     * @param dto     Öffnungszeit aus der Detail-Schnittstelle
     * @return die abgebildete Öffnungszeit-Entität
     */
    private OpeningTime toOpeningTime(final Station station, final OpeningTimeDto dto) {
        final OpeningTime openingTime = new OpeningTime();
        openingTime.setStation(station);
        openingTime.setDescription(dto.getText());
        openingTime.setStartTime(OpeningTimeParser.parse(dto.getStart()));
        openingTime.setEndTime(OpeningTimeParser.parse(dto.getEnd()));
        return openingTime;
    }

    /**
     * Ersetzt alle Ausnahmeregeln einer Tankstelle durch die gelieferten.
     *
     * @param station   betroffene Tankstelle
     * @param overrides neue Ausnahmeregeln oder {@code null}
     */
    private void replaceOverrides(final Station station, final List<String> overrides) {
        openingOverrideRepository.deleteByStation(station);
        if (overrides == null) {
            return;
        }
        overrides.forEach(description -> openingOverrideRepository.save(toOverride(station, description)));
    }

    /**
     * Bildet einen Ausnahmetext auf eine Entität ab.
     *
     * @param station     zugehörige Tankstelle
     * @param description Wortlaut der Ausnahmeregel
     * @return die abgebildete Ausnahmeregel-Entität
     */
    private OpeningOverride toOverride(final Station station, final String description) {
        final OpeningOverride override = new OpeningOverride();
        override.setStation(station);
        override.setDescription(description);
        return override;
    }

}
