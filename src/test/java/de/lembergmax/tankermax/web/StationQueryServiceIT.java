package de.lembergmax.tankermax.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.lembergmax.tankermax.web.dto.StationDto;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * Integrationstest für {@link StationQueryService} gegen eine echte Wegwerf-MariaDB. Prüft die
 * MariaDB-spezifische Fensterfunktion in {@code latestOpenPrices} (jüngster Preis je Tankstelle), die
 * Bounding-Box-Eingrenzung und die {@code openOnly}-Logik – Verhalten, das eine In-Memory-Datenbank
 * nicht zuverlässig abbilden würde. Ohne Docker wird der Test übersprungen.
 */
@SpringBootTest
@ActiveProfiles("web")
@Testcontainers(disabledWithoutDocker = true)
class StationQueryServiceIT {

    /** Mittelpunkt der Test-Region. */
    private static final double CENTER_LAT = 51.05;

    /** Mittelpunkt der Test-Region. */
    private static final double CENTER_LNG = 13.74;

    /** Wegwerf-MariaDB; die Verbindungsdaten werden via {@link ServiceConnection} eingespeist. */
    @Container
    @ServiceConnection
    static MariaDBContainer mariaDb = new MariaDBContainer("mariadb:11");

    /** Direkter Datenbankzugriff zum Anlegen der Testdaten. */
    @Autowired
    private JdbcTemplate jdbc;

    /** Prüfling. */
    @Autowired
    private StationQueryService service;

    /**
     * Konfiguriert eine Test-Region am Mittelpunkt der Testdaten und einen Platzhalter-API-Schlüssel.
     *
     * @param registry Registry für dynamisch gesetzte Test-Eigenschaften
     */
    @DynamicPropertySource
    static void configureRegion(final DynamicPropertyRegistry registry) {
        registry.add("tankerkoenig.api.key", () -> "test-key");
        registry.add("tankerkoenig.locations[0].name", () -> "TestRegion");
        registry.add("tankerkoenig.locations[0].latitude", () -> CENTER_LAT);
        registry.add("tankerkoenig.locations[0].longitude", () -> CENTER_LNG);
        registry.add("tankerkoenig.locations[0].radius-km", () -> 25);
        registry.add("tankerkoenig.locations[0].type", () -> "all");
    }

    /**
     * Legt vor jedem Test einen sauberen Datenbestand an: die Kraftstoffart Diesel, eine Tankstelle
     * S1 mit zwei Preisbeobachtungen (älter 1,500, neuer 1,600) sowie eine Tankstelle S2 ohne Preis.
     */
    @BeforeEach
    void seedData() {
        jdbc.update("DELETE FROM fuel_price");
        jdbc.update("DELETE FROM price_observation");
        jdbc.update("DELETE FROM opening_time");
        jdbc.update("DELETE FROM station");
        jdbc.update("DELETE FROM fuel_type");

        jdbc.update("INSERT INTO fuel_type (code, label) VALUES ('DIESEL', 'Diesel')");
        final Long dieselId = jdbc.queryForObject("SELECT id FROM fuel_type WHERE code = 'DIESEL'", Long.class);

        insertStation("S1", CENTER_LAT, CENTER_LNG);
        insertStation("S2", CENTER_LAT + 0.01, CENTER_LNG + 0.01);

        insertOpenPrice("S1", dieselId, 7200, "1.500");
        insertOpenPrice("S1", dieselId, 3600, "1.600");
    }

    /**
     * Legt eine Tankstelle an.
     *
     * @param id  Kennung
     * @param lat Breite
     * @param lng Länge
     */
    private void insertStation(final String id, final double lat, final double lng) {
        jdbc.update("INSERT INTO station (id, name, latitude, longitude, whole_day, detail_fetch_failures) "
                + "VALUES (?, ?, ?, ?, false, 0)", id, "Tankstelle " + id, lat, lng);
    }

    /**
     * Legt eine geöffnete Preisbeobachtung samt Preis an.
     *
     * @param stationId  Kennung der Tankstelle
     * @param fuelTypeId Kennung der Kraftstoffart
     * @param secondsAgo Alter der Beobachtung in Sekunden
     * @param amount     Preis je Liter
     */
    private void insertOpenPrice(final String stationId, final Long fuelTypeId, final long secondsAgo, final String amount) {
        final Timestamp observedAt = Timestamp.from(Instant.now().minusSeconds(secondsAgo));
        jdbc.update("INSERT INTO price_observation (station_id, observed_at, status) VALUES (?, ?, 'OPEN')",
                stationId, observedAt);
        final Long observationId = jdbc.queryForObject(
                "SELECT id FROM price_observation WHERE station_id = ? AND observed_at = ?",
                Long.class, stationId, observedAt);
        jdbc.update("INSERT INTO fuel_price (observation_id, fuel_type_id, amount) VALUES (?, ?, ?)",
                observationId, fuelTypeId, new java.math.BigDecimal(amount));
    }

    /**
     * {@code stations} liefert je Tankstelle den jüngsten offenen Preis (1,600 statt der älteren
     * 1,500) und schließt die preislose Tankstelle nicht aus, solange {@code openOnly} false ist.
     */
    @Test
    void liefertJuengstenPreisUndAlleTankstellen() {
        final List<StationDto> stations = service.stations("TestRegion", "DIESEL", false);
        assertEquals(2, stations.size());

        final StationDto s1 = stations.stream().filter(s -> s.id().equals("S1")).findFirst().orElseThrow();
        assertTrue(s1.isOpen());
        assertNotNull(s1.priceNow());
        assertEquals(1.6, s1.priceNow(), 0.0001);

        final StationDto s2 = stations.stream().filter(s -> s.id().equals("S2")).findFirst().orElseThrow();
        assertNull(s2.priceNow());
    }

    /**
     * Mit {@code openOnly} wird die preislose (als geschlossen geltende) Tankstelle ausgeblendet.
     */
    @Test
    void openOnlyBlendetTankstelleOhnePreisAus() {
        final List<StationDto> stations = service.stations("TestRegion", "DIESEL", true);
        assertEquals(1, stations.size());
        assertEquals("S1", stations.get(0).id());
    }

    /**
     * Die Regionszählung erfasst beide Tankstellen im Radius.
     */
    @Test
    void regionZaehltTankstellenImRadius() {
        assertEquals(2, service.regions().get(0).count());
    }

}
