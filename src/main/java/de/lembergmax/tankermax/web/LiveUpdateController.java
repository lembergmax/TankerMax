package de.lembergmax.tankermax.web;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Stellt den Echtzeit-Datenstrom (Server-Sent Events) bereit, über den verbundene Browser bei neu
 * erfassten Preisen zum Nachladen aufgefordert werden. Nur im Profil {@code web} aktiv.
 *
 * <p>Bewusst von {@link WebApiController} getrennt, da dessen zentrale Fehlerbehandlung
 * ({@link WebApiExceptionHandler}) auf die JSON-Lese-Endpunkte zugeschnitten ist und nicht auf den
 * langlebigen Ereignisstrom angewandt werden soll.</p>
 */
@RestController
@RequestMapping("/api")
@Profile("web")
@RequiredArgsConstructor
public class LiveUpdateController {

    /** Dienst, der die Verbindungen verwaltet und Aktualisierungen verteilt. */
    private final LiveUpdateService liveUpdateService;

    /**
     * Öffnet eine SSE-Verbindung für den anfragenden Browser.
     *
     * <p>Antwortkopfzeilen unterbinden Zwischenspeicherung und Pufferung durch vorgelagerte Proxys,
     * damit die Ereignisse ohne Verzögerung beim Browser ankommen.</p>
     *
     * @param response laufende Antwort, deren Kopfzeilen für den Stream ergänzt werden
     * @return die SSE-Verbindung für den anfragenden Browser
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(final HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return liveUpdateService.subscribe();
    }

}
