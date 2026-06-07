package de.lembergmax.tankermax.polling.client;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.lembergmax.tankermax.polling.config.TankerkoenigProperties;
import org.junit.jupiter.api.Test;

/**
 * Tests für {@link TankerkoenigRateLimiter}: Mindestabstand zwischen Aufrufen, Sperrpause nach
 * einer Ratenlimit-Antwort und korrektes Verhalten bei Unterbrechung.
 */
class TankerkoenigRateLimiterTest {

    /** Mindestabstand in Millisekunden für die Tests (klein, damit die Tests schnell bleiben). */
    private static final long MIN_INTERVAL_MS = 120;

    /** Sperrpause in Millisekunden für die Tests. */
    private static final long COOLDOWN_MS = 150;

    /**
     * Erzeugt einen Ratenbegrenzer mit den Test-Zeiten.
     *
     * @return Ratenbegrenzer mit kurzem Mindestabstand und kurzer Sperrpause
     */
    private TankerkoenigRateLimiter newLimiter() {
        final TankerkoenigProperties properties = new TankerkoenigProperties();
        properties.getApi().setMinRequestIntervalMs(MIN_INTERVAL_MS);
        properties.getApi().setRateLimitCooldownMs(COOLDOWN_MS);
        return new TankerkoenigRateLimiter(properties);
    }

    /**
     * Der zweite Aufruf wird mindestens um den Mindestabstand verzögert.
     */
    @Test
    void haeltMindestabstandEin() {
        final TankerkoenigRateLimiter limiter = newLimiter();
        limiter.awaitSlot();
        final long start = System.nanoTime();
        limiter.awaitSlot();
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= MIN_INTERVAL_MS - 40,
                "Zweiter Aufruf sollte etwa den Mindestabstand warten, war aber " + elapsedMs + " ms");
    }

    /**
     * Nach {@code penalize()} wartet der nächste Aufruf mindestens die Sperrpause ab dem Fehlschlag.
     */
    @Test
    void penalizeSetztSperrpause() {
        final TankerkoenigRateLimiter limiter = newLimiter();
        limiter.awaitSlot();
        limiter.penalize();
        final long start = System.nanoTime();
        limiter.awaitSlot();
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= COOLDOWN_MS - 40,
                "Nach penalize sollte etwa die Sperrpause gewartet werden, war aber " + elapsedMs + " ms");
    }

    /**
     * Wird der Thread während des Wartens unterbrochen, bricht der Aufruf ab und der
     * Unterbrechungsstatus bleibt erhalten, damit kein verfrühter API-Aufruf erfolgt.
     */
    @Test
    void unterbrechungBrichtAbUndErhaeltStatus() {
        final TankerkoenigProperties properties = new TankerkoenigProperties();
        properties.getApi().setMinRequestIntervalMs(10_000);
        final TankerkoenigRateLimiter limiter = new TankerkoenigRateLimiter(properties);
        limiter.awaitSlot();
        Thread.currentThread().interrupt();
        assertThrows(IllegalStateException.class, limiter::awaitSlot);
        assertTrue(Thread.interrupted(), "Unterbrechungsstatus sollte wiederhergestellt sein");
    }

}
