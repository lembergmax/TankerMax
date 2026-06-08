package de.lembergmax.tankermax.polling.client;

import de.lembergmax.tankermax.polling.client.dto.StationDetailResponse;
import de.lembergmax.tankermax.polling.client.dto.StationListResponse;
import de.lembergmax.tankermax.polling.config.Location;
import de.lembergmax.tankermax.polling.config.TankerkoenigProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

/**
 * Zugriff auf die JSON-Schnittstellen der Tankerkönig-API.
 *
 * <p>Jeder Aufruf wird über den {@link TankerkoenigRateLimiter} geführt, sodass
 * das Ratenlimit der API über alle Endpunkte und Threads hinweg eingehalten wird.</p>
 *
 * <p>Ein transienter Fehler (Verbindungs- oder Lese-Timeout) wird bis zur konfigurierten Höchstzahl
 * mit wachsender Backoff-Pause erneut versucht; jeder Versuch läuft erneut durch den Ratenbegrenzer.
 * Die Sperrpause wird erst beim endgültigen Scheitern verhängt, damit ein einzelner Aussetzer, der
 * sich beim erneuten Versuch erledigt, nicht unnötig alle Aufrufe drosselt.</p>
 */
@Component
@Profile("ingest")
public class TankerkoenigApiClient {

    /** Sortierung der Umkreissuche nach Entfernung. */
    private static final String SORT_BY_DISTANCE = "dist";

    /** HTTP-Status für ein überschrittenes Ratenlimit. */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /** HTTP-Status, mit dem die API ein Ratenlimit signalisiert. */
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;

    /** Vorkonfigurierter HTTP-Client mit der Basis-URL der API. */
    private final RestClient restClient;

    /** Konfiguration mit dem API-Schlüssel. */
    private final TankerkoenigProperties properties;

    /** Globaler Ratenbegrenzer für alle API-Aufrufe. */
    private final TankerkoenigRateLimiter rateLimiter;

    /** Höchstzahl der Versuche je Aufruf bei transienten Fehlern. */
    private final int maxAttempts;

    /** Grund-Wartezeit in Millisekunden vor dem ersten erneuten Versuch (verdoppelt sich danach). */
    private final long retryBackoffMs;

    /** Zähler der ausgelösten Sperrpausen (Ratenlimit oder endgültiges Timeout), für die Metriken. */
    private final Counter rateLimitCounter;

    /** Zähler der erneuten Versuche nach transienten Fehlern, für die Metriken. */
    private final Counter retryCounter;

    /**
     * Erzeugt den API-Client und richtet die Wiederholungsparameter und Kennzahlen ein.
     *
     * @param restClient    vorkonfigurierter HTTP-Client mit der Basis-URL der API
     * @param properties    Konfiguration mit API-Schlüssel und Wiederholungsparametern
     * @param rateLimiter   globaler Ratenbegrenzer für alle API-Aufrufe
     * @param meterRegistry Registry für die Ratenlimit- und Wiederholungskennzahlen
     */
    public TankerkoenigApiClient(final RestClient restClient,
                                 final TankerkoenigProperties properties,
                                 final TankerkoenigRateLimiter rateLimiter,
                                 final MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.maxAttempts = properties.getApi().getMaxAttempts();
        this.retryBackoffMs = properties.getApi().getRetryBackoffMs();
        this.rateLimitCounter = meterRegistry.counter("tankermax.api.ratelimit");
        this.retryCounter = meterRegistry.counter("tankermax.api.retry");
    }

    /**
     * Ruft alle Tankstellen samt aktueller Preise innerhalb eines Ortes ab.
     *
     * @param location abzufragender Ort mit Mittelpunkt, Radius und Kraftstoffart
     * @return Antwort der Listen-Schnittstelle
     */
    public StationListResponse fetchStations(final Location location) {
        return execute(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/list.php")
                        .queryParam("lat", location.getLatitude())
                        .queryParam("lng", location.getLongitude())
                        .queryParam("rad", location.getRadiusKm())
                        .queryParam("sort", SORT_BY_DISTANCE)
                        .queryParam("type", location.getType())
                        .queryParam("apikey", properties.getApi().getKey())
                        .build())
                .retrieve()
                .body(StationListResponse.class));
    }

    /**
     * Ruft die Detaildaten einer einzelnen Tankstelle ab.
     *
     * @param stationId Kennung der Tankstelle
     * @return Antwort der Detail-Schnittstelle
     */
    public StationDetailResponse fetchStationDetail(final String stationId) {
        return execute(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/detail.php")
                        .queryParam("id", stationId)
                        .queryParam("apikey", properties.getApi().getKey())
                        .build())
                .retrieve()
                .body(StationDetailResponse.class));
    }

    /**
     * Führt einen API-Aufruf nach Einhaltung der Drosselung aus, wiederholt ihn bei einem
     * transienten Fehler und verhängt eine Sperrpause, wenn die API ein Ratenlimit signalisiert
     * oder der Aufruf endgültig an einem Timeout scheitert.
     *
     * <p>Ein Verbindungs- oder Lese-Timeout ({@link ResourceAccessException}) gilt als transient und
     * wird bis zur Höchstzahl der Versuche mit wachsender Backoff-Pause erneut versucht; erst beim
     * letzten Versuch wird die Sperrpause verhängt. Ein Timeout ist im Tankerkönig-Kontext die
     * wahrscheinlichste Erscheinungsform einer aktiven IP-Sperre, die sich als Timeout statt als
     * HTTP-Fehler äußert. Eine Ratenlimit-Antwort ({@link HttpStatusCodeException} mit HTTP 503/429)
     * wird hingegen nicht erneut versucht, sondern löst sofort die Sperrpause aus. Übrige
     * HTTP-Fehler (etwa 400/500) werden unverändert weitergereicht.</p>
     *
     * @param apiCall auszuführender API-Aufruf
     * @param <T>     Typ der Antwort
     * @return Ergebnis des API-Aufrufs
     */
    private <T> T execute(final Supplier<T> apiCall) {
        int attempt = 1;
        while (true) {
            rateLimiter.awaitSlot();
            try {
                return apiCall.get();
            } catch (final ResourceAccessException ex) {
                if (attempt >= maxAttempts) {
                    rateLimiter.penalize();
                    rateLimitCounter.increment();
                    throw ex;
                }
                retryCounter.increment();
                backoffBeforeRetry(attempt);
                attempt++;
            } catch (final HttpStatusCodeException ex) {
                if (isRateLimited(ex)) {
                    rateLimiter.penalize();
                    rateLimitCounter.increment();
                }
                throw ex;
            }
        }
    }

    /**
     * Wartet vor einem erneuten Versuch die mit jedem Versuch wachsende Backoff-Pause ab.
     *
     * <p>Wird der Thread während des Wartens unterbrochen (etwa beim Herunterfahren), wird der
     * Unterbrechungsstatus wiederhergestellt und der Aufruf abgebrochen.</p>
     *
     * @param attempt bisheriger Versuch (beginnend bei 1), bestimmt die Länge der Pause
     * @throws IllegalStateException wenn das Warten unterbrochen wurde
     */
    private void backoffBeforeRetry(final int attempt) {
        final long waitMillis = retryBackoffMs * (1L << (attempt - 1));
        try {
            Thread.sleep(waitMillis);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Backoff-Wartezeit vor erneutem API-Versuch wurde unterbrochen.", ex);
        }
    }

    /**
     * Prüft, ob eine Fehlerantwort ein überschrittenes Ratenlimit anzeigt.
     *
     * @param ex aufgetretene HTTP-Fehlerantwort
     * @return {@code true}, wenn die Antwort ein Ratenlimit signalisiert
     */
    private boolean isRateLimited(final HttpStatusCodeException ex) {
        final int statusCode = ex.getStatusCode().value();
        return statusCode == HTTP_SERVICE_UNAVAILABLE || statusCode == HTTP_TOO_MANY_REQUESTS;
    }

}
