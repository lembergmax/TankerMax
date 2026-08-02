package de.lembergmax.tankermax.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Verteilt Echtzeit-Benachrichtigungen über neu erfasste Preise an alle verbundenen Browser
 * (Server-Sent Events). Nur im Profil {@code web} aktiv.
 *
 * <p>Ein fester Takt ({@link #pulse()}) prüft die Datenbank günstig auf neue Beobachtungen, indem
 * er den höchsten Primärschlüssel der Beobachtungstabelle liest – dieser wächst mit jedem Einfügen
 * und lässt sich über den Primärschlüssel-Index ohne Tabellenscan ermitteln. Steigt der Wert, sind
 * neue Preise eingetroffen: die kurzlebigen Lese-Caches werden geleert (damit ein anschließendes
 * Nachladen des Browsers sofort die frischen Daten erhält statt eines bis zu eine Minute alten
 * Cache-Eintrags) und ein {@code prices}-Ereignis an alle Verbindungen gesendet. Liegt keine
 * Änderung vor, hält ein Heartbeat-Kommentar die Verbindungen offen und deckt zugleich
 * abgebrochene Verbindungen auf, deren fehlgeschlagener Schreibvorgang sie aus der Liste entfernt.</p>
 *
 * <p>Die eigentliche Datenübertragung übernimmt der Browser über die {@code EventSource}-API, die
 * Verbindungsabbrüche selbsttätig erkennt und die Verbindung neu aufbaut; der Server muss dafür
 * keinen Zustand je Browser vorhalten.</p>
 */
@Service
@Profile("web")
public class LiveUpdateService {

    /** Logger dieser Klasse. */
    private static final Logger LOG = LoggerFactory.getLogger(LiveUpdateService.class);

    /** Name des SSE-Ereignisses, das den Browser zum Nachladen frischer Preise auffordert. */
    private static final String PRICE_EVENT = "prices";

    /** Abfrage des höchsten Beobachtungsschlüssels als günstiges Signal für neue Daten. */
    private static final String MAX_VERSION_SQL = "SELECT MAX(id) FROM price_observation";

    /** Zugriff auf die Datenbank zur Änderungserkennung. */
    private final JdbcTemplate jdbc;

    /** Verwaltung der kurzlebigen Lese-Caches, die bei neuen Daten geleert werden. */
    private final CacheManager cacheManager;

    /**
     * Zeitlimit einer einzelnen SSE-Verbindung in Millisekunden; {@code 0} bedeutet kein Limit
     * (der Heartbeat hält die Verbindung offen und erkennt abgebrochene Verbindungen).
     */
    private final long emitterTimeoutMs;

    /** Aktive SSE-Verbindungen; nebenläufig sicher für gleichzeitiges Hinzufügen, Iterieren und Entfernen. */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Zuletzt beobachteter höchster Beobachtungsschlüssel; {@code -1}, solange noch keiner ermittelt wurde. */
    private final AtomicLong lastSeenVersion = new AtomicLong(-1);

    /**
     * Erzeugt den Dienst mit den benötigten Abhängigkeiten und der konfigurierten Verbindungsdauer.
     *
     * @param jdbc             Zugriff auf die Datenbank zur Änderungserkennung
     * @param cacheManager     Verwaltung der bei neuen Daten zu leerenden Lese-Caches
     * @param emitterTimeoutMs Zeitlimit einer SSE-Verbindung in Millisekunden ({@code 0} = kein Limit)
     */
    public LiveUpdateService(final JdbcTemplate jdbc,
                             final CacheManager cacheManager,
                             @Value("${tankermax.web.live.emitter-timeout-ms:0}") final long emitterTimeoutMs) {
        this.jdbc = jdbc;
        this.cacheManager = cacheManager;
        this.emitterTimeoutMs = emitterTimeoutMs;
    }

    /**
     * Meldet einen neuen Browser an und liefert die zugehörige SSE-Verbindung.
     *
     * <p>Die Verbindung wird bei Abschluss, Zeitüberschreitung oder Fehler automatisch wieder
     * abgemeldet. Ein einleitendes Kommentar erzwingt das Senden der Antwortkopfzeilen, damit der
     * Browser die Verbindung sofort als hergestellt erkennt.</p>
     *
     * @return die SSE-Verbindung für den anfragenden Browser
     */
    public SseEmitter subscribe() {
        final SseEmitter emitter = new SseEmitter(emitterTimeoutMs <= 0 ? 0L : emitterTimeoutMs);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(throwable -> emitters.remove(emitter));
        emitters.add(emitter);
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (final IOException | IllegalStateException ex) {
            drop(emitter, ex);
        }
        return emitter;
    }

    /**
     * Prüft den Datenbestand auf neue Preise und benachrichtigt die verbundenen Browser.
     *
     * <p>Bei neuen Daten werden die Lese-Caches geleert und ein {@code prices}-Ereignis gesendet;
     * andernfalls hält ein Heartbeat-Kommentar die Verbindungen offen. Der Takt wird über
     * {@code tankermax.web.live.pulse-interval-ms} konfiguriert. Ein vorübergehender Datenbankfehler
     * wird übersprungen, ohne bestehende Verbindungen zu beenden.</p>
     */
    @Scheduled(
            initialDelayString = "${tankermax.web.live.pulse-interval-ms:5000}",
            fixedDelayString = "${tankermax.web.live.pulse-interval-ms:5000}"
    )
    public void pulse() {
        final long current = currentVersion();
        if (current < 0) {
            return;
        }
        final long previous = lastSeenVersion.getAndSet(current);
        final boolean changed = previous >= 0 && current != previous;
        if (changed) {
            evictReadCaches();
        }
        if (emitters.isEmpty()) {
            return;
        }
        broadcast(changed, current);
    }

    /**
     * Ermittelt den höchsten Beobachtungsschlüssel als Versionssignal.
     *
     * @return höchster Schlüssel (bei leerer Tabelle {@code 0}) oder {@code -1} bei einem Datenbankfehler
     */
    private long currentVersion() {
        try {
            final Long max = jdbc.queryForObject(MAX_VERSION_SQL, Long.class);
            return max == null ? 0L : max;
        } catch (final DataAccessException ex) {
            LOG.debug("Änderungserkennung übersprungen, Datenbank momentan nicht erreichbar: {}", ex.getMessage());
            return -1;
        }
    }

    /**
     * Sendet allen verbundenen Browsern entweder ein Aktualisierungsereignis oder einen Heartbeat.
     *
     * <p>Eine Verbindung, deren Schreibvorgang fehlschlägt (etwa nach einem Verbindungsabbruch), wird
     * entfernt und beendet; der Browser baut die Verbindung anschließend selbsttätig neu auf.</p>
     *
     * @param changed {@code true}, wenn neue Preise vorliegen und ein {@code prices}-Ereignis gesendet wird
     * @param version aktueller höchster Beobachtungsschlüssel, der als Ereigniskennung mitgesendet wird
     */
    private void broadcast(final boolean changed, final long version) {
        for (final SseEmitter emitter : emitters) {
            try {
                emitter.send(eventFor(changed, version));
            } catch (final IOException | IllegalStateException ex) {
                drop(emitter, ex);
            }
        }
    }

    /**
     * Entfernt eine fehlerhafte Verbindung aus der Liste und beendet sie; ein bereits abgeschlossener
     * Abschluss wird dabei verschluckt.
     *
     * @param emitter zu verwerfende Verbindung
     * @param cause   Ursache des Fehlers
     */
    private void drop(final SseEmitter emitter, final Throwable cause) {
        emitters.remove(emitter);
        try {
            emitter.completeWithError(cause);
        } catch (final Exception ignored) {
        }
    }

    /**
     * Erstellt das je Verbindung zu sendende Ereignis: ein {@code prices}-Ereignis bei neuen Daten,
     * sonst einen Heartbeat-Kommentar.
     *
     * @param changed {@code true} für ein Aktualisierungsereignis, {@code false} für einen Heartbeat
     * @param version aktueller höchster Beobachtungsschlüssel als Ereigniskennung
     * @return das zu sendende SSE-Ereignis
     */
    private static SseEventBuilder eventFor(final boolean changed, final long version) {
        if (changed) {
            final String value = Long.toString(version);
            return SseEmitter.event().name(PRICE_EVENT).id(value).data(value);
        }
        return SseEmitter.event().comment("ping");
    }

    /**
     * Leert die kurzlebigen Lese-Caches, sodass das nächste Nachladen frische Preise liefert.
     */
    private void evictReadCaches() {
        clearCache("stations");
        clearCache("regions");
    }

    /**
     * Leert einen benannten Cache, sofern er vorhanden ist.
     *
     * @param name Name des zu leerenden Caches
     */
    private void clearCache(final String name) {
        final Cache cache = cacheManager.getCache(name);
        if (cache != null) {
            cache.clear();
        }
    }

}
