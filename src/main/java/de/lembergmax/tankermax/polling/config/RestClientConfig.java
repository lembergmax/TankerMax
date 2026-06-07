package de.lembergmax.tankermax.polling.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Stellt den vorkonfigurierten {@link RestClient} für die Tankerkönig-API bereit.
 */
@Configuration
@Profile("ingest")
public class RestClientConfig {

    /**
     * Erzeugt einen {@link RestClient} mit konfigurierten Zeitlimits, dessen Basis-URL
     * auf den konfigurierten Tankerkönig-Endpunkt zeigt.
     *
     * @param properties Konfiguration mit Basis-URL und Zeitlimits der API
     * @return einsatzbereiter {@link RestClient}
     */
    @Bean
    public RestClient tankerkoenigRestClient(final TankerkoenigProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getApi().getBaseUrl())
                .requestFactory(timeoutAwareRequestFactory(properties))
                .build();
    }

    /**
     * Erzeugt eine Anfragefabrik mit Verbindungs- und Lesezeitlimit aus der Konfiguration.
     *
     * @param properties Konfiguration mit den Zeitlimits ({@code tankerkoenig.api.*-timeout-ms})
     * @return Anfragefabrik mit gesetzten Zeitlimits
     */
    private SimpleClientHttpRequestFactory timeoutAwareRequestFactory(final TankerkoenigProperties properties) {
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getApi().getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getApi().getReadTimeoutMs()));
        return requestFactory;
    }

}
