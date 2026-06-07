package de.lembergmax.tankermax.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Gebündelte Konfiguration für den Zugriff auf die Tankerkönig-API, die
 * abzufragenden Orte sowie die Abfrage- und Anreicherungszeitpläne.
 *
 * <p>Sämtliche hier gebündelten Werte stammen aus der externen Konfigurationsdatei
 * ({@code application.properties}) und werden über Bean-Validation beim
 * Anwendungsstart geprüft, sodass eine Fehlkonfiguration (etwa eine zu kleine
 * Drosselung, die eine IP-Sperre provoziert) sofort und nicht erst beim ersten
 * API-Aufruf auffällt.</p>
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "tankerkoenig")
public class TankerkoenigProperties {

    /** Zugangs- und Endpunktdaten der Tankerkönig-API. */
    @Valid
    private final Api api = new Api();

    /** Orte, die abwechselnd abgefragt werden. */
    @Valid
    private List<Location> locations = new ArrayList<>();

    /** Zeitplan für die rotierende Preisabfrage. */
    @Valid
    private final Poll poll = new Poll();

    /** Zeitplan für die Anreicherung der Tankstellen um Detaildaten. */
    @Valid
    private final Enrichment enrichment = new Enrichment();

    /**
     * Zugangs- und Endpunktdaten der Tankerkönig-API sowie die globale Drosselung.
     */
    @Getter
    @Setter
    public static class Api {

        /** Basis-URL der JSON-Schnittstelle ohne abschließenden Schrägstrich. */
        @NotBlank
        private String baseUrl = "https://creativecommons.tankerkoenig.de/json";

        /** Persönlicher API-Schlüssel für die Authentifizierung. */
        @NotBlank
        private String key;

        /** Mindestabstand in Millisekunden zwischen zwei beliebigen API-Aufrufen; begrenzt das gesamte API-Aufkommen auf höchstens einen Aufruf pro Minute (60 s + 2 s Puffer). */
        @Positive
        private long minRequestIntervalMs = 62_000;

        /** Sperrpause in Millisekunden für alle API-Aufrufe nach einer Ratenlimit-Antwort (HTTP 503/429). */
        @Positive
        private long rateLimitCooldownMs = 120_000;

        /** Zeitlimit in Millisekunden für den Verbindungsaufbau zur API. */
        @Positive
        private int connectTimeoutMs = 10_000;

        /** Zeitlimit in Millisekunden für das Lesen der API-Antwort. */
        @Positive
        private int readTimeoutMs = 15_000;
    }

    /**
     * Zeitplan für die rotierende Preisabfrage.
     */
    @Getter
    @Setter
    public static class Poll {

        /** Verzögerung in Millisekunden bis zur ersten Abfrage nach Anwendungsstart. */
        @PositiveOrZero
        private long initialDelayMs = 10_000;

        /** Abstand in Millisekunden zwischen zwei Abfragen; pro Abstand wird genau ein Ort abgefragt. */
        @Positive
        private long intervalMs = 300_000;

        /** Höchstzahl gleichzeitig konfigurierbarer Orte. */
        @Min(1)
        private int maxLocations = 15;

        /**
         * Höchstzahl aufeinanderfolgender Abfragezyklen, in denen die Preisabfrage wegen noch nicht
         * angereicherter Tankstellen pausiert, ohne dass die Anreicherung Fortschritt macht. Danach
         * wird die Preisabfrage fortgesetzt, damit ein dauerhaft fehlschlagender Detailabruf (etwa
         * eine IP-Sperre) die Preiserfassung nicht unbegrenzt blockiert.
         */
        @Min(1)
        private int maxStallCycles = 5;
    }

    /**
     * Zeitplan und Stapelgröße für die Anreicherung der Tankstellen um Detaildaten.
     */
    @Getter
    @Setter
    public static class Enrichment {

        /** Verzögerung in Millisekunden bis zur ersten Anreicherung nach Anwendungsstart. */
        @PositiveOrZero
        private long initialDelayMs = 30_000;

        /** Abstand in Millisekunden zwischen zwei Anreicherungsläufen. */
        @Positive
        private long intervalMs = 300_000;

        /** Anzahl der Tankstellen, die je Anreicherungslauf um Detaildaten ergänzt werden. */
        @Min(1)
        private int batchSize = 10;
    }

}
