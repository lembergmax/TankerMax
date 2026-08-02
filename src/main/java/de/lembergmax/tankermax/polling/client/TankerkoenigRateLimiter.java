package de.lembergmax.tankermax.polling.client;

import de.lembergmax.tankermax.polling.config.TankerkoenigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Globaler Ratenbegrenzer für sämtliche Aufrufe der Tankerkönig-API.
 *
 * <p>Stellt über alle Endpunkte und Threads hinweg einen Mindestabstand zwischen
 * zwei API-Aufrufen sicher und verhängt nach einer Ratenlimit-Antwort eine
 * längere Sperrpause für alle weiteren Aufrufe. So wird das Ratenlimit der API
 * auch bei gleichzeitiger Preisabfrage und Detail-Anreicherung eingehalten.</p>
 */
@Component
@Profile("ingest")
public class TankerkoenigRateLimiter {

    /** Logger dieser Klasse. */
    private static final Logger LOG = LoggerFactory.getLogger(TankerkoenigRateLimiter.class);

    /** Mindestabstand in Millisekunden zwischen zwei API-Aufrufen. */
    private final long minIntervalMs;

    /** Sperrpause in Millisekunden nach einer Ratenlimit-Antwort. */
    private final long rateLimitCooldownMs;

    /** Sperrobjekt zur threadsicheren Vergabe der Aufrufzeitpunkte. */
    private final Object lock = new Object();

    /** Frühester erlaubter Zeitpunkt des nächsten Aufrufs als Epochenmillisekunde. */
    private long nextAllowedAtMillis;

    /**
     * Erzeugt den Ratenbegrenzer aus der Konfiguration.
     *
     * @param properties Konfiguration mit Mindestabstand und Sperrpause
     */
    public TankerkoenigRateLimiter(final TankerkoenigProperties properties) {
        this.minIntervalMs = properties.getApi().getMinRequestIntervalMs();
        this.rateLimitCooldownMs = properties.getApi().getRateLimitCooldownMs();
    }

    /**
     * Reserviert den nächsten freien Aufrufzeitpunkt und wartet, bis dieser erreicht ist.
     */
    public void awaitSlot() {
        final long slotMillis;
        synchronized (lock) {
            final long now = System.currentTimeMillis();
            slotMillis = Math.max(now, nextAllowedAtMillis);
            nextAllowedAtMillis = slotMillis + minIntervalMs;
        }
        sleepUntil(slotMillis);
    }

    /**
     * Setzt nach einer Ratenlimit-Antwort die Sperrpause für alle weiteren Aufrufe.
     *
     * <p>Die Pause wird ab dem Zeitpunkt des Fehlschlags gemessen und nicht auf einen
     * bereits reservierten Aufruf-Slot aufaddiert, damit die tatsächliche Sperrdauer der
     * protokollierten {@code rateLimitCooldownMs} entspricht.</p>
     */
    public void penalize() {
        synchronized (lock) {
            nextAllowedAtMillis = System.currentTimeMillis() + rateLimitCooldownMs;
        }
        LOG.warn("Ratenlimit erreicht – weitere API-Aufrufe werden für {} ms ausgesetzt.", rateLimitCooldownMs);
    }

    /**
     * Wartet bis zum angegebenen Zeitpunkt.
     *
     * <p>Wird der Thread während des Wartens unterbrochen (etwa beim Herunterfahren),
     * wird der Unterbrechungsstatus wiederhergestellt und der Aufruf abgebrochen, damit
     * der nachfolgende API-Aufruf nicht verfrüht und damit unter Umgehung der Drosselung
     * erfolgt.</p>
     *
     * @param targetMillis Zielzeitpunkt als Epochenmillisekunde
     * @throws IllegalStateException wenn das Warten unterbrochen wurde
     */
    private void sleepUntil(final long targetMillis) {
        final long waitMillis = targetMillis - System.currentTimeMillis();
        if (waitMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(waitMillis);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Warten auf den nächsten API-Aufrufzeitpunkt wurde unterbrochen.", ex);
        }
    }

}
