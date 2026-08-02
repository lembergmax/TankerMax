package de.lembergmax.tankermax.polling.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.lembergmax.tankermax.polling.client.dto.StationListResponse;
import de.lembergmax.tankermax.polling.config.Location;
import de.lembergmax.tankermax.polling.config.TankerkoenigProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Unit-Tests für {@link TankerkoenigApiClient}: Wiederholung bei transienten Fehlern und die
 * Verzweigung der Sperrpause (nur bei Timeout-Erschöpfung und HTTP 503/429, nicht bei 400). Der
 * {@link RestClient} und der {@link TankerkoenigRateLimiter} sind gemockt, es erfolgt kein
 * Netzwerkzugriff.
 */
class TankerkoenigApiClientTest {

    /** Gemockter HTTP-Client. */
    private final RestClient restClient = mock(RestClient.class);

    /** Gemockte Antwortstufe des Fluent-API, deren {@code body(...)} je Test bestückt wird. */
    private final RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

    /** Gemockter Ratenbegrenzer zur Prüfung von {@code awaitSlot}/{@code penalize}. */
    private final TankerkoenigRateLimiter rateLimiter = mock(TankerkoenigRateLimiter.class);

    /** Registry für die Kennzahlen. */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /**
     * Erzeugt einen Client mit kleiner Versuchszahl und kurzer Backoff-Pause und verdrahtet das
     * Fluent-API des {@link RestClient} bis zur Antwortstufe.
     *
     * @param maxAttempts Höchstzahl der Versuche je Aufruf
     * @return einsatzbereiter, vollständig gemockter Client
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private TankerkoenigApiClient newClient(final int maxAttempts) {
        final TankerkoenigProperties properties = new TankerkoenigProperties();
        properties.getApi().setMaxAttempts(maxAttempts);
        properties.getApi().setRetryBackoffMs(1);
        final RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        final RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(any(Function.class))).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        return new TankerkoenigApiClient(restClient, properties, rateLimiter, meterRegistry);
    }

    /**
     * Liefert den aktuellen Wert eines Zählers.
     *
     * @param name Name des Zählers
     * @return Zählerstand
     */
    private double counter(final String name) {
        return meterRegistry.counter(name).count();
    }

    /**
     * Ein wiederholt auftretendes Timeout wird bis zur Höchstzahl erneut versucht; danach wird die
     * Sperrpause verhängt und die Ausnahme weitergereicht.
     */
    @Test
    void wiederholtesTimeoutVerhaengtNachVersuchenSperrpause() {
        final TankerkoenigApiClient client = newClient(3);
        when(responseSpec.body(StationListResponse.class)).thenThrow(new ResourceAccessException("timeout"));
        assertThrows(ResourceAccessException.class, () -> client.fetchStations(new Location()));
        verify(rateLimiter, times(3)).awaitSlot();
        verify(rateLimiter, times(1)).penalize();
        assertEquals(2.0, counter("tankermax.api.retry"));
        assertEquals(1.0, counter("tankermax.api.ratelimit"));
    }

    /**
     * Ein nur einmal auftretendes Timeout wird erfolgreich wiederholt; es gibt keine Sperrpause.
     */
    @Test
    void einmaligesTimeoutWirdErfolgreichWiederholt() {
        final TankerkoenigApiClient client = newClient(3);
        final StationListResponse expected = new StationListResponse();
        when(responseSpec.body(StationListResponse.class))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(expected);
        assertSame(expected, client.fetchStations(new Location()));
        verify(rateLimiter, times(2)).awaitSlot();
        verify(rateLimiter, never()).penalize();
        assertEquals(1.0, counter("tankermax.api.retry"));
    }

    /**
     * Eine Ratenlimit-Antwort (HTTP 503) verhängt sofort die Sperrpause, ohne erneuten Versuch.
     */
    @Test
    void ratenlimitAntwortVerhaengtSperrpauseOhneWiederholung() {
        final TankerkoenigApiClient client = newClient(3);
        when(responseSpec.body(StationListResponse.class))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(HttpServerErrorException.class, () -> client.fetchStations(new Location()));
        verify(rateLimiter, times(1)).awaitSlot();
        verify(rateLimiter, times(1)).penalize();
        assertEquals(0.0, counter("tankermax.api.retry"));
    }

    /**
     * Ein gewöhnlicher Client-Fehler (HTTP 400) löst weder Sperrpause noch Wiederholung aus.
     */
    @Test
    void clientfehlerLoestKeineSperrpauseAus() {
        final TankerkoenigApiClient client = newClient(3);
        when(responseSpec.body(StationListResponse.class))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));
        assertThrows(HttpClientErrorException.class, () -> client.fetchStations(new Location()));
        verify(rateLimiter, times(1)).awaitSlot();
        verify(rateLimiter, never()).penalize();
    }

}
