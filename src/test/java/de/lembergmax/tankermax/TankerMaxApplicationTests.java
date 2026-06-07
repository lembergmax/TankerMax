package de.lembergmax.tankermax;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * Integrationstest, der den vollständigen Anwendungskontext gegen eine echte
 * Wegwerf-MariaDB hochfährt. Dadurch wird zugleich geprüft, dass die
 * Flyway-Migration greift (das Schema also angelegt wird) und Hibernates
 * {@code ddl-auto=validate} es ohne Beanstandung akzeptiert – der entscheidende
 * Beweis, dass Migration und Entitäten zusammenpassen.
 *
 * <p>Ohne laufenden Docker-Daemon wird der Test übersprungen ({@code disabledWithoutDocker}),
 * sodass lokale Builds ohne Docker dennoch grün sind; in der CI mit Docker läuft er.</p>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TankerMaxApplicationTests {

    /** Wegwerf-MariaDB; die Verbindungsdaten werden via {@link ServiceConnection} eingespeist. */
    @Container
    @ServiceConnection
    static MariaDBContainer mariaDb = new MariaDBContainer("mariadb:11");

    /**
     * Verschiebt die geplanten Abrufe weit in die Zukunft und setzt einen Platzhalter-Schlüssel,
     * damit während des kurzen Tests keine echten API-Aufrufe ausgelöst werden.
     *
     * @param registry Registry für dynamisch gesetzte Test-Eigenschaften
     */
    @DynamicPropertySource
    static void scheduleFarInFuture(final DynamicPropertyRegistry registry) {
        registry.add("tankerkoenig.poll.initial-delay-ms", () -> 3_600_000);
        registry.add("tankerkoenig.enrichment.initial-delay-ms", () -> 3_600_000);
        registry.add("tankerkoenig.api.key", () -> "test-key");
    }

    /**
     * Stellt sicher, dass der Anwendungskontext vollständig lädt.
     */
    @Test
    void contextLoads() {
    }

}
