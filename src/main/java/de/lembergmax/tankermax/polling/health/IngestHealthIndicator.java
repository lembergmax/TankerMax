package de.lembergmax.tankermax.polling.health;

import de.lembergmax.tankermax.polling.config.TankerkoenigProperties;
import de.lembergmax.tankermax.polling.service.LocationPricePollService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Gesundheitsanzeige des Ingest: meldet {@code DOWN}, wenn seit zu langer Zeit keine erfolgreiche
 * Preisabfrage mehr erfolgt ist.
 *
 * <p>So lässt sich ein schleichender Stillstand (etwa ein dauerhafter Ratenlimit-Cooldown oder ein
 * stehengebliebener Aufgabenplaner) maschinell erkennen, statt ihn nur an ausbleibenden neuen
 * Preisen im Dashboard zu bemerken. Der Status ist über {@code /actuator/health} abrufbar, sofern
 * der eingebettete Web-Server läuft (Profil {@code web}); im reinen {@code ingest}-Lauf ohne
 * Web-Server stehen die Protokollausgaben zur Verfügung.</p>
 */
@Component("ingest")
@Profile("ingest")
@RequiredArgsConstructor
public class IngestHealthIndicator implements HealthIndicator {

    /** Abfragedienst, der den Zeitpunkt der letzten erfolgreichen Preisabfrage bereitstellt. */
    private final LocationPricePollService pollService;

    /** Konfiguration mit der zulässigen Stillstandsdauer. */
    private final TankerkoenigProperties properties;

    /**
     * Bewertet den Gesundheitszustand des Ingest anhand des Zeitpunkts der letzten erfolgreichen
     * Preisabfrage.
     *
     * @return {@code UP}, solange die letzte erfolgreiche Abfrage innerhalb der zulässigen Dauer
     *         liegt oder noch keine Abfrage stattgefunden hat; sonst {@code DOWN}
     */
    @Override
    public Health health() {
        final Instant lastSuccess = pollService.getLastSuccessfulPollAt();
        if (lastSuccess == null) {
            return Health.up().withDetail("letzteErfolgreicheAbfrage", "noch keine").build();
        }
        final Duration sinceLastSuccess = Duration.between(lastSuccess, Instant.now());
        final long thresholdMs = properties.getPoll().getHealthStaleThresholdMs();
        final Health.Builder builder = sinceLastSuccess.toMillis() > thresholdMs ? Health.down() : Health.up();
        return builder
                .withDetail("letzteErfolgreicheAbfrage", lastSuccess.toString())
                .withDetail("stillstandMs", sinceLastSuccess.toMillis())
                .build();
    }

}
