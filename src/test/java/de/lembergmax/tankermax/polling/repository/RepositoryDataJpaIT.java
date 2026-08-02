package de.lembergmax.tankermax.polling.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.lembergmax.tankermax.polling.domain.Brand;
import de.lembergmax.tankermax.polling.domain.FuelType;
import de.lembergmax.tankermax.polling.domain.OpeningTime;
import de.lembergmax.tankermax.polling.domain.Station;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * Integrationstest der Repositories gegen eine echte Wegwerf-MariaDB. Führt die zur Laufzeit
 * aufgelösten abgeleiteten Abfragen und das modifizierende JPQL-Delete tatsächlich aus, sodass ein
 * durch Umbenennung gebrochener Methoden- oder Feldname auffällt. Jeder Test läuft in einer
 * zurückgerollten Transaktion. Ohne Docker wird der Test übersprungen.
 */
@SpringBootTest
@ActiveProfiles("web")
@Transactional
@Testcontainers(disabledWithoutDocker = true)
class RepositoryDataJpaIT {

    /** Wegwerf-MariaDB; die Verbindungsdaten werden via {@link ServiceConnection} eingespeist. */
    @Container
    @ServiceConnection
    static MariaDBContainer mariaDb = new MariaDBContainer("mariadb:11");

    /** Entity-Manager zum direkten Persistieren und Leeren des Persistenzkontexts. */
    @PersistenceContext
    private EntityManager em;

    /** Prüfling: Tankstellen-Repository. */
    @Autowired
    private StationRepository stationRepository;

    /** Prüfling: Öffnungszeiten-Repository. */
    @Autowired
    private OpeningTimeRepository openingTimeRepository;

    /** Prüfling: Marken-Repository. */
    @Autowired
    private BrandRepository brandRepository;

    /** Prüfling: Kraftstoffart-Repository. */
    @Autowired
    private FuelTypeRepository fuelTypeRepository;

    /**
     * Setzt einen Platzhalter-API-Schlüssel, damit der Kontext lädt, ohne echte API-Aufrufe zu erfordern.
     *
     * @param registry Registry für dynamisch gesetzte Test-Eigenschaften
     */
    @DynamicPropertySource
    static void apiKey(final DynamicPropertyRegistry registry) {
        registry.add("tankerkoenig.api.key", () -> "test-key");
    }

    /**
     * Persistiert eine Tankstelle mit Kennung und optionalem Anreicherungszeitpunkt.
     *
     * @param id               Kennung
     * @param detailsFetchedAt Anreicherungszeitpunkt oder {@code null}
     * @return persistierte Tankstelle
     */
    private Station persistStation(final String id, final Instant detailsFetchedAt) {
        final Station station = new Station();
        station.setId(id);
        station.setName("Tankstelle " + id);
        station.setDetailsFetchedAt(detailsFetchedAt);
        em.persist(station);
        return station;
    }

    /**
     * Die abgeleiteten Abfragen über {@code detailsFetchedAt} liefern und zählen genau die noch nicht
     * angereicherten Tankstellen.
     */
    @Test
    void findetUndZaehltNichtAngereicherteTankstellen() {
        persistStation("A", null);
        persistStation("B", Instant.now());
        em.flush();
        em.clear();

        assertEquals(1, stationRepository.countByDetailsFetchedAtIsNull());
        final List<Station> pending = stationRepository.findByDetailsFetchedAtIsNull(PageRequest.of(0, 10));
        assertEquals(1, pending.size());
        assertEquals("A", pending.get(0).getId());
    }

    /**
     * Das modifizierende JPQL-Delete entfernt alle Öffnungszeiten einer Tankstelle.
     */
    @Test
    void loeschtOeffnungszeitenEinerTankstelle() {
        final Station station = persistStation("C", Instant.now());
        persistOpeningTime(station, "Mo-Fr");
        persistOpeningTime(station, "Sa");
        em.flush();

        openingTimeRepository.deleteByStation(station);
        em.flush();
        em.clear();

        assertEquals(0, openingTimeRepository.count());
    }

    /**
     * Die abgeleiteten Suchen nach Markenname und Kraftstoffschlüssel finden den passenden Eintrag.
     */
    @Test
    void findetMarkeUndKraftstoffart() {
        final Brand brand = new Brand();
        brand.setName("Aral");
        em.persist(brand);
        final FuelType fuelType = new FuelType();
        fuelType.setCode("DIESEL");
        fuelType.setLabel("Diesel");
        em.persist(fuelType);
        em.flush();
        em.clear();

        assertTrue(brandRepository.findByName("Aral").isPresent());
        assertTrue(brandRepository.findByName("Unbekannt").isEmpty());
        assertTrue(fuelTypeRepository.findByCode("DIESEL").isPresent());
    }

    /**
     * Persistiert eine Öffnungszeit zu einer Tankstelle.
     *
     * @param station     zugehörige Tankstelle
     * @param description Tagesbereich
     */
    private void persistOpeningTime(final Station station, final String description) {
        final OpeningTime openingTime = new OpeningTime();
        openingTime.setStation(station);
        openingTime.setDescription(description);
        em.persist(openingTime);
    }

}
