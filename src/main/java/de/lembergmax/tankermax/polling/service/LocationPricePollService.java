package de.lembergmax.tankermax.polling.service;

import de.lembergmax.tankermax.polling.client.TankerkoenigApiClient;
import de.lembergmax.tankermax.polling.client.dto.StationListItem;
import de.lembergmax.tankermax.polling.client.dto.StationListResponse;
import de.lembergmax.tankermax.polling.config.Location;
import de.lembergmax.tankermax.polling.config.TankerkoenigProperties;
import de.lembergmax.tankermax.polling.repository.StationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fragt die konfigurierten Orte rotierend ab: Je Intervall wird genau ein Ort
 * abgefragt, sodass bei mehreren Orten jeder Ort im Abstand von
 * {@code Anzahl der Orte × Intervall} an die Reihe kommt.
 *
 * <p>Solange noch Tankstellen ohne Detaildaten vorhanden sind, pausiert die
 * Preisabfrage, damit die Detail-Anreicherung das gesamte Aufruf-Budget erhält
 * und so früh wie möglich abgeschlossen ist.</p>
 *
 * <p>Die geplante Methode {@link #pollNextLocation()} wird von Spring nie nebenläufig
 * zu sich selbst ausgeführt; die veränderlichen Felder zur Pausen- und
 * Stillstandserkennung werden daher ausschließlich aus diesem einen Thread gelesen und
 * geschrieben und benötigen keine zusätzliche Synchronisierung.</p>
 */
@Service
@Profile("ingest")
public class LocationPricePollService {

    /** Logger dieser Klasse. */
    private static final Logger LOG = LoggerFactory.getLogger(LocationPricePollService.class);

    /** Client für den Zugriff auf die Tankerkönig-API. */
    private final TankerkoenigApiClient apiClient;

    /** Dienst zum Speichern der Stammdaten. */
    private final StationCatalogService stationCatalogService;

    /** Dienst zum Speichern der Preisbeobachtungen. */
    private final PriceRecordingService priceRecordingService;

    /** Repository für Tankstellen, genutzt zur Prüfung des Anreicherungsstands. */
    private final StationRepository stationRepository;

    /** Effektiv verwendete Orte, begrenzt auf die konfigurierte Höchstzahl. */
    private final List<Location> locations;

    /** Fortlaufender Zähler zur Auswahl des nächsten Ortes in der Rotation. */
    private final AtomicInteger rotationCounter = new AtomicInteger();

    /** Führt das Speichern einer Tankstelle samt Preisen in einer einzigen Transaktion aus. */
    private final TransactionTemplate txTemplate;

    /** Höchstzahl aufeinanderfolgender Pausenzyklen ohne Anreicherungsfortschritt, danach wird die Preisabfrage erzwungen. */
    private final int maxStallCycles;

    /** Zähler der erfolgreich erfassten Tankstellen, für die Metriken. */
    private final Counter recordedCounter;

    /** Zähler der nicht erfassbaren Tankstellen, für die Metriken. */
    private final Counter failedCounter;

    /** Zähler der erzwungenen Fortsetzungen wegen blockierter Anreicherung, für die Metriken. */
    private final Counter enrichmentStallCounter;

    /** Zeitpunkt der letzten erfolgreichen Preisabfrage; {@code null}, solange noch keine erfolgte. */
    private volatile Instant lastSuccessfulPollAt;

    /** Merkt, ob die Preisabfrage wegen der laufenden Vorbereitungsphase pausiert, um wiederholte Pausenmeldungen zu vermeiden. */
    private boolean pricePollingPaused;

    /** Zuletzt beobachtete Anzahl noch nicht angereicherter Tankstellen (zur Fortschrittserkennung). */
    private long lastPendingCount = Long.MAX_VALUE;

    /** Zahl der bisher ohne Anreicherungsfortschritt verstrichenen Pausenzyklen. */
    private int stallCycles;

    /** Merkt, ob bereits gewarnt wurde, dass die Anreicherung nicht vorankommt. */
    private boolean stallWarned;

    /**
     * Erzeugt den Abfragedienst und übernimmt die konfigurierten Orte.
     *
     * @param apiClient             Client für den Zugriff auf die Tankerkönig-API
     * @param stationCatalogService Dienst zum Speichern der Stammdaten
     * @param priceRecordingService Dienst zum Speichern der Preisbeobachtungen
     * @param stationRepository     Repository für Tankstellen
     * @param properties            Konfiguration mit Orten und Höchstzahl
     * @param transactionManager    Transaktionsverwaltung für das atomare Speichern je Tankstelle
     * @param meterRegistry         Registry für die Erfassungs- und Stillstandskennzahlen
     */
    public LocationPricePollService(final TankerkoenigApiClient apiClient,
                                    final StationCatalogService stationCatalogService,
                                    final PriceRecordingService priceRecordingService,
                                    final StationRepository stationRepository,
                                    final TankerkoenigProperties properties,
                                    final PlatformTransactionManager transactionManager,
                                    final MeterRegistry meterRegistry) {
        this.apiClient = apiClient;
        this.stationCatalogService = stationCatalogService;
        this.priceRecordingService = priceRecordingService;
        this.stationRepository = stationRepository;
        this.locations = limitLocations(properties.getLocations(), properties.getPoll().getMaxLocations());
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.maxStallCycles = properties.getPoll().getMaxStallCycles();
        this.recordedCounter = meterRegistry.counter("tankermax.stations.recorded");
        this.failedCounter = meterRegistry.counter("tankermax.stations.failed");
        this.enrichmentStallCounter = meterRegistry.counter("tankermax.enrichment.stalls");
    }

    /**
     * Liefert den Zeitpunkt der letzten erfolgreichen Preisabfrage für die Gesundheitsprüfung.
     *
     * @return Zeitpunkt der letzten erfolgreichen Abfrage oder {@code null}, solange noch keine erfolgte
     */
    public Instant getLastSuccessfulPollAt() {
        return lastSuccessfulPollAt;
    }

    /**
     * Fragt den nächsten Ort der Rotation ab und persistiert dessen Preise.
     *
     * <p>Die Abfrage wird übersprungen, solange noch Tankstellen ohne Detaildaten
     * vorhanden sind, damit die Anreicherung Vorrang hat. Der Zeitplan wird über
     * {@code tankerkoenig.poll.*} konfiguriert.</p>
     */
    @Scheduled(
            initialDelayString = "${tankerkoenig.poll.initial-delay-ms}",
            fixedDelayString = "${tankerkoenig.poll.interval-ms}"
    )
    public void pollNextLocation() {
        if (locations.isEmpty()) {
            LOG.warn("Keine Orte konfiguriert, Abfrage wird übersprungen.");
            return;
        }
        try {
            runRotationCycle();
        } catch (final RuntimeException ex) {
            LOG.warn("Preisabfrage-Zyklus abgebrochen, erneuter Versuch im nächsten Intervall: {}", ex.getMessage());
        }
    }

    /**
     * Führt einen einzelnen Rotationszyklus aus: prüft den Anreicherungsstand, pausiert
     * gegebenenfalls und fragt andernfalls den nächsten Ort der Rotation ab.
     *
     * <p>Bewusst getrennt von {@link #pollNextLocation()}, damit deren Fehlerbehandlung
     * jede Ausnahme dieses Zyklus – auch der Datenbankabfrage des Anreicherungsstands –
     * abfängt und der {@code @Scheduled}-Lauf nicht bei jedem Datenbankausfall einen
     * vollständigen Stacktrace protokolliert.</p>
     */
    private void runRotationCycle() {
        final long pending = stationRepository.countByDetailsFetchedAtIsNull();
        if (pending == 0) {
            resetEnrichmentTracking();
        } else if (!enrichmentStalled(pending)) {
            pauseForEnrichment(pending);
            return;
        }
        pricePollingPaused = false;
        final Location location = locations.get(Math.floorMod(rotationCounter.getAndIncrement(), locations.size()));
        try {
            pollLocation(location);
        } catch (final RuntimeException ex) {
            LOG.warn("Abfrage für Ort '{}' fehlgeschlagen: {}", location.getName(), ex.getMessage());
        }
    }

    /**
     * Protokolliert einmalig, dass die Preisabfrage zugunsten der laufenden
     * Detail-Anreicherung pausiert.
     *
     * @param pending Anzahl der noch nicht angereicherten Tankstellen
     */
    private void pauseForEnrichment(final long pending) {
        if (!pricePollingPaused) {
            LOG.info("Preisabfrage pausiert – Vorbereitungsphase (Detail-Anreicherung) läuft ({} offen).", pending);
            pricePollingPaused = true;
        }
    }

    /**
     * Setzt die Fortschrittserkennung der Anreicherung zurück, sobald keine Tankstelle
     * mehr offen ist.
     *
     * <p>Ohne dieses Zurücksetzen bliebe {@link #lastPendingCount} auf dem letzten
     * Tiefststand stehen; ein später neu entdeckter Anreicherungsrückstand (etwa nach
     * neuen Tankstellen oder einem Neustart) würde dann sofort als Stillstand gewertet
     * und die Preisabfrage fälschlich erzwungen, obwohl die Anreicherung normal arbeitet.</p>
     */
    private void resetEnrichmentTracking() {
        lastPendingCount = Long.MAX_VALUE;
        stallCycles = 0;
        stallWarned = false;
    }

    /**
     * Stellt fest, ob die Detail-Anreicherung nicht mehr vorankommt, und entscheidet so, ob die
     * Preisabfrage trotz offener Tankstellen fortgesetzt werden soll.
     *
     * <p>Solange die Zahl offener Tankstellen sinkt, gilt die Anreicherung als fortschreitend und
     * die Preisabfrage pausiert. Bleibt die Zahl über {@code maxStallCycles} Zyklen unverändert oder
     * steigt sie, gilt die Anreicherung als blockiert (etwa durch eine API-Sperre) und die
     * Preisabfrage wird wieder zugelassen, damit die Preiserfassung nicht unbegrenzt blockiert.</p>
     *
     * @param pending Anzahl der noch nicht angereicherten Tankstellen (größer 0)
     * @return {@code true}, wenn die Anreicherung blockiert ist und die Preisabfrage erzwungen wird
     */
    private boolean enrichmentStalled(final long pending) {
        if (pending < lastPendingCount) {
            lastPendingCount = pending;
            stallCycles = 0;
            stallWarned = false;
            return false;
        }
        stallCycles++;
        if (stallCycles < maxStallCycles) {
            return false;
        }
        if (!stallWarned) {
            LOG.warn("Detail-Anreicherung kommt seit {} Zyklen nicht voran ({} offen); Preisabfrage wird fortgesetzt.",
                    stallCycles, pending);
            stallWarned = true;
            enrichmentStallCounter.increment();
        }
        return true;
    }

    /**
     * Ruft einen Ort ab und speichert dessen Tankstellen samt Preisen.
     *
     * <p>Jede Tankstelle wird in einer eigenen Transaktion und mit eigener
     * Fehlerbehandlung verarbeitet, damit ein einzelner Fehlschlag (etwa eine
     * Eindeutigkeitsverletzung) nur diese Tankstelle überspringt und nicht den Rest
     * des Laufs verwirft.</p>
     *
     * <p>Mehrfach in derselben Antwort enthaltene Tankstellen werden vorab nach Kennung
     * entdoppelt, da sie sonst beim zweiten Auftreten gegen die Eindeutigkeitsbedingung
     * {@code (station_id, observed_at)} liefen und die Fehlerzahl mit harmlosen Doubletten
     * verschmutzen würden.</p>
     *
     * @param location abzufragender Ort
     */
    private void pollLocation(final Location location) {
        final StationListResponse response = apiClient.fetchStations(location);
        if (response == null || !response.isOk() || response.getStations() == null) {
            LOG.warn("Abfrage für Ort '{}' lieferte keine verwertbaren Daten.", location.getName());
            return;
        }
        final Instant observedAt = Instant.now();
        final List<StationListItem> stations = deduplicateById(response.getStations());
        int failed = 0;
        for (final StationListItem item : stations) {
            if (recordStation(item, observedAt)) {
                recordedCounter.increment();
            } else {
                failed++;
            }
        }
        failedCounter.increment(failed);
        lastSuccessfulPollAt = Instant.now();
        LOG.info("Ort '{}': {} Tankstellen abgefragt, {} erfasst, {} fehlgeschlagen.",
                location.getName(), stations.size(), stations.size() - failed, failed);
    }

    /**
     * Entfernt mehrfach auftretende Tankstellen einer Antwort anhand ihrer Kennung und behält dabei
     * das jeweils erste Auftreten in der ursprünglichen Reihenfolge.
     *
     * @param stations Tankstellen der Antwort, möglicherweise mit Doubletten
     * @return Tankstellen ohne Doubletten in Reihenfolge des ersten Auftretens
     */
    private static List<StationListItem> deduplicateById(final List<StationListItem> stations) {
        final Map<String, StationListItem> uniqueById = new LinkedHashMap<>();
        for (final StationListItem item : stations) {
            uniqueById.putIfAbsent(item.getId(), item);
        }
        return List.copyOf(uniqueById.values());
    }

    /**
     * Speichert Stammdaten und Preisbeobachtung einer einzelnen Tankstelle in einer
     * eigenen Transaktion und fängt dabei Fehler ab.
     *
     * @param item       Tankstelle samt Preisen aus der Listen-Schnittstelle
     * @param observedAt Zeitpunkt der Abfrage
     * @return {@code true}, wenn die Tankstelle erfasst wurde, sonst {@code false}
     */
    private boolean recordStation(final StationListItem item, final Instant observedAt) {
        try {
            txTemplate.executeWithoutResult(status -> {
                stationCatalogService.saveFromListItem(item);
                priceRecordingService.recordFromListItem(item, observedAt);
            });
            return true;
        } catch (final RuntimeException ex) {
            LOG.warn("Tankstelle '{}' konnte nicht erfasst werden: {}", item.getId(), ex.getMessage());
            return false;
        }
    }

    /**
     * Begrenzt die konfigurierten Orte auf die zulässige Höchstzahl.
     *
     * @param configured   konfigurierte Orte
     * @param maxLocations Höchstzahl zulässiger Orte
     * @return die tatsächlich zu verwendenden Orte
     */
    private static List<Location> limitLocations(final List<Location> configured, final int maxLocations) {
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        if (configured.size() <= maxLocations) {
            return List.copyOf(configured);
        }
        LOG.warn("Es sind {} Orte konfiguriert; es werden nur die ersten {} verwendet.",
                configured.size(), maxLocations);
        return List.copyOf(configured.subList(0, maxLocations));
    }

}
