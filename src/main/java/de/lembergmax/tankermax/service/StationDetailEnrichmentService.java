package de.lembergmax.tankermax.service;

import de.lembergmax.tankermax.client.TankerkoenigApiClient;
import de.lembergmax.tankermax.client.dto.StationDetailResponse;
import de.lembergmax.tankermax.config.TankerkoenigProperties;
import de.lembergmax.tankermax.domain.Station;
import de.lembergmax.tankermax.repository.StationRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reichert gespeicherte Tankstellen um Detaildaten (Öffnungszeiten,
 * Ausnahmeregeln, Bundesland) aus der Detail-Schnittstelle an.
 *
 * <p>Diese Anreicherung bildet die Vorbereitungsphase: Solange noch Tankstellen
 * ohne Detaildaten vorhanden sind, pausiert die Preisabfrage und die Anreicherung
 * arbeitet alle offenen Tankstellen am Stück ab. Beginn, Fortschritt je Tankstelle
 * und Ende der Phase werden samt geschätzter und tatsächlicher Dauer protokolliert.
 * Den Abstand zwischen den Abrufen erzwingt der globale Ratenbegrenzer im
 * {@link TankerkoenigApiClient}.</p>
 *
 * <p>Die geplante Methode {@link #enrichPendingStations()} wird von Spring nie nebenläufig
 * zu sich selbst ausgeführt; die veränderlichen Felder zum Phasenzustand werden daher
 * ausschließlich aus diesem einen Thread gelesen und geschrieben und benötigen keine
 * zusätzliche Synchronisierung.</p>
 */
@Service
@RequiredArgsConstructor
public class StationDetailEnrichmentService {

    /** Logger dieser Klasse. */
    private static final Logger LOG = LoggerFactory.getLogger(StationDetailEnrichmentService.class);

    /** Format für Uhrzeitangaben in den Protokollmeldungen. */
    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Anzahl Millisekunden je Sekunde, zur Umrechnung des Aufrufabstands. */
    private static final long MILLIS_PER_SECOND = 1000;

    /** Anzahl Sekunden je Stunde, zur Aufteilung einer Dauer. */
    private static final long SECONDS_PER_HOUR = 3600;

    /** Anzahl Sekunden je Minute, zur Aufteilung einer Dauer. */
    private static final long SECONDS_PER_MINUTE = 60;

    /** Skalierungsfaktor zur Umrechnung eines Anteils in Prozent. */
    private static final long PERCENT_SCALE = 100;

    /** Client für den Zugriff auf die Tankerkönig-API. */
    private final TankerkoenigApiClient apiClient;

    /** Dienst zum Speichern der Detaildaten. */
    private final StationCatalogService stationCatalogService;

    /** Repository für Tankstellen. */
    private final StationRepository stationRepository;

    /** Konfiguration mit Stapelgröße und Aufrufabstand. */
    private final TankerkoenigProperties properties;

    /** Startzeitpunkt der laufenden Vorbereitungsphase; {@code null}, wenn keine Phase aktiv ist. */
    private Instant preparationStartedAt;

    /** Anzahl der in der laufenden Vorbereitungsphase bereits angereicherten Tankstellen. */
    private int preparationEnrichedCount;

    /**
     * Führt die Vorbereitungsphase aus: reichert alle offenen Tankstellen am Stück
     * an und protokolliert Beginn, Fortschritt je Tankstelle und Ende mit Zeit- und
     * Dauerangaben.
     *
     * <p>Bei einem Fehler wird der Lauf abgebrochen und im nächsten Durchgang
     * fortgesetzt. Der Zeitplan wird über {@code tankerkoenig.enrichment.*}
     * konfiguriert.</p>
     */
    @Scheduled(
            initialDelayString = "${tankerkoenig.enrichment.initial-delay-ms}",
            fixedDelayString = "${tankerkoenig.enrichment.interval-ms}"
    )
    public void enrichPendingStations() {
        final long pendingCount = stationRepository.countByDetailsFetchedAtIsNull();
        if (pendingCount == 0) {
            return;
        }
        beginPreparationPhase(pendingCount);
        drainPendingStations();
        final long remainingCount = stationRepository.countByDetailsFetchedAtIsNull();
        if (remainingCount == 0) {
            finishPreparationPhase();
        } else {
            LOG.info("Vorbereitungsphase unterbrochen: noch {} Tankstellen offen, Fortsetzung im nächsten Lauf.",
                    remainingCount);
        }
    }

    /**
     * Protokolliert den Beginn der Vorbereitungsphase, sofern noch keine läuft.
     *
     * @param pendingCount Anzahl der zu Beginn offenen Tankstellen
     */
    private void beginPreparationPhase(final long pendingCount) {
        if (preparationStartedAt != null) {
            return;
        }
        preparationStartedAt = Instant.now();
        preparationEnrichedCount = 0;
        final long estimatedSeconds = pendingCount * requestIntervalSeconds();
        final Instant estimatedEnd = preparationStartedAt.plusSeconds(estimatedSeconds);
        LOG.info("===== Vorbereitungsphase START =====");
        LOG.info("Offene Tankstellen: {} | Start {} | geschätzte Dauer {} | voraussichtliches Ende {}",
                pendingCount, CLOCK_FORMAT.format(preparationStartedAt), formatDuration(estimatedSeconds),
                CLOCK_FORMAT.format(estimatedEnd));
    }

    /**
     * Reichert die offenen Tankstellen stapelweise an, bis keine mehr offen sind
     * oder ein Fehler auftritt, und protokolliert nach jeder Tankstelle den Fortschritt.
     */
    private void drainPendingStations() {
        while (true) {
            final List<Station> pendingStations = stationRepository.findByDetailsFetchedAtIsNull(
                    PageRequest.of(0, properties.getEnrichment().getBatchSize()));
            if (pendingStations.isEmpty()) {
                return;
            }
            try {
                for (final Station station : pendingStations) {
                    final boolean enriched = enrichStation(station.getId());
                    if (enriched) {
                        preparationEnrichedCount++;
                    }
                    logStationProgress(station, enriched);
                }
            } catch (final RuntimeException ex) {
                LOG.warn("Anreicherung nach Fehler abgebrochen: {}", ex.getMessage());
                return;
            }
        }
    }

    /**
     * Protokolliert den Fortschritt nach einer einzelnen Tankstelle samt Zähler,
     * Anteil, verbleibender Anzahl, neuer Endschätzung und bisheriger Laufzeit.
     *
     * @param station  gerade verarbeitete Tankstelle
     * @param enriched {@code true}, wenn Detaildaten übernommen wurden
     */
    private void logStationProgress(final Station station, final boolean enriched) {
        final long remaining = stationRepository.countByDetailsFetchedAtIsNull();
        final long total = preparationEnrichedCount + remaining;
        final long percentDone = total == 0 ? PERCENT_SCALE : preparationEnrichedCount * PERCENT_SCALE / total;
        final long elapsedSeconds = Duration.between(preparationStartedAt, Instant.now()).getSeconds();
        final Instant estimatedEnd = Instant.now().plusSeconds(remaining * requestIntervalSeconds());
        LOG.info("Anreicherung {}/{} ({}%): '{}' {} | noch {} offen | ETA {} | läuft seit {}",
                preparationEnrichedCount, total, percentDone, formatAddress(station),
                enriched ? "angereichert" : "ohne Detaildaten, übersprungen",
                remaining, CLOCK_FORMAT.format(estimatedEnd), formatDuration(elapsedSeconds));
    }

    /**
     * Setzt die Tankstellenadresse für die Protokollausgabe zu einer Zeile zusammen,
     * etwa {@code Hauptstraße 5, 12345 Musterstadt}.
     *
     * <p>Leere oder fehlende Adressbestandteile werden ausgelassen. Liefert die API
     * keinerlei Adresse, wird ersatzweise der Anzeigename verwendet.</p>
     *
     * @param station Tankstelle, deren Adresse formatiert wird
     * @return zusammengesetzte Adresszeile, ersatzweise der Anzeigename
     */
    private String formatAddress(final Station station) {
        final String streetLine = joinNonBlank(" ", station.getStreet(), station.getHouseNumber());
        final String cityLine = joinNonBlank(" ", station.getPostCode(), station.getPlace());
        final String address = joinNonBlank(", ", streetLine, cityLine);
        return address.isBlank() ? station.getName() : address;
    }

    /**
     * Fügt die übergebenen Bestandteile mit dem Trennzeichen zusammen und überspringt
     * dabei {@code null}- und leere Werte.
     *
     * @param delimiter Trennzeichen zwischen den Bestandteilen
     * @param parts     zusammenzufügende Bestandteile
     * @return zusammengefügte Zeichenkette ohne leere Bestandteile
     */
    private String joinNonBlank(final String delimiter, final String... parts) {
        return Arrays.stream(parts)
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(delimiter));
    }

    /**
     * Protokolliert den Abschluss der Vorbereitungsphase mit tatsächlicher Endzeit
     * und Dauer und beendet die Phase.
     */
    private void finishPreparationPhase() {
        final Instant finishedAt = Instant.now();
        final long actualSeconds = Duration.between(preparationStartedAt, finishedAt).getSeconds();
        LOG.info("Angereichert: {} Tankstellen | Start {} | Ende {} | tatsächliche Dauer {}",
                preparationEnrichedCount, CLOCK_FORMAT.format(preparationStartedAt), CLOCK_FORMAT.format(finishedAt),
                formatDuration(actualSeconds));
        LOG.info("===== Vorbereitungsphase ENDE – Preisabfrage wird aufgenommen =====");
        preparationStartedAt = null;
        preparationEnrichedCount = 0;
    }

    /**
     * Lädt die Detaildaten einer Tankstelle und speichert sie.
     *
     * <p>Liefert die API eine gültige Antwort ohne Detaildaten, wird die
     * Tankstelle als geprüft markiert, damit sie die Warteschlange nicht dauerhaft
     * blockiert. Übertragungsfehler werden weitergereicht, damit der Lauf abbricht
     * und es später erneut versucht.</p>
     *
     * @param stationId Kennung der Tankstelle
     * @return {@code true}, wenn Detaildaten übernommen wurden, sonst {@code false}
     */
    private boolean enrichStation(final String stationId) {
        final StationDetailResponse response = apiClient.fetchStationDetail(stationId);
        if (response == null || !response.isOk() || response.getStation() == null) {
            stationCatalogService.markDetailFetchAttempted(stationId);
            return false;
        }
        stationCatalogService.applyDetail(response.getStation());
        return true;
    }

    /**
     * Liefert den konfigurierten Aufrufabstand des Ratenbegrenzers in Sekunden.
     *
     * @return Aufrufabstand in Sekunden, mindestens eine Sekunde
     */
    private long requestIntervalSeconds() {
        return Math.max(1, properties.getApi().getMinRequestIntervalMs() / MILLIS_PER_SECOND);
    }

    /**
     * Formatiert eine Dauer in Sekunden in eine lesbare Angabe.
     *
     * @param totalSeconds Dauer in Sekunden
     * @return lesbare Dauer, etwa {@code 1 h 39 min} oder {@code 12 min 30 s}
     */
    private String formatDuration(final long totalSeconds) {
        final long hours = totalSeconds / SECONDS_PER_HOUR;
        final long minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        final long seconds = totalSeconds % SECONDS_PER_MINUTE;
        if (hours > 0) {
            return String.format("%d h %d min", hours, minutes);
        }
        return String.format("%d min %d s", minutes, seconds);
    }

}
