package de.lembergmax.tankermax.web;

import de.lembergmax.tankermax.web.dto.ForecastResponseDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-Schnittstelle für die KI-Preisvorhersage. Liefert ausschließlich die zuvor berechneten und
 * gespeicherten Vorhersagen; es findet kein Training und keine Modellauswertung statt. Nur im Profil
 * {@code web} aktiv.
 *
 * <p>Die Eingabevalidierung entspricht dem übrigen Web-Layer: Längenbegrenzung der Kennung und
 * Prüfung der Kraftstoffkennung. Fehlerfälle werden über {@link WebApiExceptionHandler} einheitlich
 * beantwortet.</p>
 */
@RestController
@RequestMapping("/api")
@Profile("web")
@Validated
@RequiredArgsConstructor
public class ForecastApiController {

    /** Höchstlänge der frei übergebenen Tankstellen-Kennung, als Schutz vor Missbrauch. */
    private static final int MAX_IDENTIFIER_LENGTH = 64;

    /** Zugriff auf die gespeicherten Vorhersagen. */
    private final ForecastQueryService forecastService;

    /**
     * Liefert die Vorhersage einer Tankstelle für einen Kraftstoff.
     *
     * @param station Kennung der Tankstelle
     * @param fuel    Frontend-Kennung des Kraftstoffs (e5/e10/diesel)
     * @return Vorhersage samt Band, Tanktipp und Treffer-Statistik
     */
    @GetMapping("/forecast")
    public ForecastResponseDto forecast(@RequestParam @NotBlank @Size(max = MAX_IDENTIFIER_LENGTH) final String station,
                                        @RequestParam final String fuel) {
        return forecastService.forecast(station, requireKnownFuel(fuel));
    }

    /**
     * Prüft die Frontend-Kraftstoffkennung und liefert den zugehörigen Datenbank-Code.
     *
     * @param fuel Frontend-Kennung (e5/e10/diesel)
     * @return Datenbank-Code des Kraftstoffs
     * @throws IllegalArgumentException wenn der Kraftstoff unbekannt ist (führt zu HTTP 400)
     */
    private static String requireKnownFuel(final String fuel) {
        if (!FuelCodes.isKnown(fuel)) {
            throw new IllegalArgumentException("Unbekannter Kraftstoff: " + fuel);
        }
        return FuelCodes.toDb(fuel);
    }

}
