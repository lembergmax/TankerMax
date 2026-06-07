package de.lembergmax.tankermax.polling.client;

import de.lembergmax.tankermax.polling.client.dto.StationDetailResponse;
import de.lembergmax.tankermax.polling.client.dto.StationListResponse;
import de.lembergmax.tankermax.polling.config.Location;
import de.lembergmax.tankermax.polling.config.TankerkoenigProperties;
import lombok.RequiredArgsConstructor;
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
 */
@Component
@RequiredArgsConstructor
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
     * Führt einen API-Aufruf nach Einhaltung der Drosselung aus und verhängt eine
     * Sperrpause, wenn die API ein Ratenlimit signalisiert.
     *
     * <p>Eine Ratenlimit-Antwort ({@link HttpStatusCodeException} mit HTTP 503/429)
     * sowie ein Verbindungs- oder Lese-Timeout ({@link ResourceAccessException}) lösen
     * die Sperrpause aus. Letzteres ist im Tankerkönig-Kontext die wahrscheinlichste
     * Erscheinungsform einer aktiven IP-Sperre, die sich laut API-Verhalten als Timeout
     * statt als HTTP-Fehler äußert. Die ursprüngliche Ausnahme wird in beiden Fällen
     * weitergereicht, damit der aufrufende {@code @Scheduled}-Lauf sie behandeln kann.</p>
     *
     * @param apiCall auszuführender API-Aufruf
     * @param <T>     Typ der Antwort
     * @return Ergebnis des API-Aufrufs
     */
    private <T> T execute(final Supplier<T> apiCall) {
        rateLimiter.awaitSlot();
        try {
            return apiCall.get();
        } catch (final ResourceAccessException ex) {
            rateLimiter.penalize();
            throw ex;
        } catch (final HttpStatusCodeException ex) {
            if (isRateLimited(ex)) {
                rateLimiter.penalize();
            }
            throw ex;
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
