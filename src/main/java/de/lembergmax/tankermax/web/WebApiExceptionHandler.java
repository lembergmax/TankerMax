package de.lembergmax.tankermax.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Zentrale Fehlerbehandlung der Web-API. Wandelt Ausnahmen aus {@link WebApiController}
 * in ein vorhersehbares, bereinigtes Antwortformat ({@link ProblemDetail}, RFC&nbsp;7807) um,
 * statt rohe HTTP-500-Whitelabel-Seiten mit Stacktrace auszuliefern.
 *
 * <p>Erbt von {@link ResponseEntityExceptionHandler}, damit die Standard-Spring-MVC-Fehler
 * (fehlende oder ungültige Anfrageparameter) weiterhin als HTTP&nbsp;400 beantwortet werden;
 * ergänzt werden nur die anwendungsspezifischen Fälle. Bewusst auf {@link WebApiController}
 * begrenzt, damit andere Antworten unberührt bleiben. Nur im Profil {@code web}.</p>
 */
@RestControllerAdvice(assignableTypes = WebApiController.class)
@Profile("web")
public class WebApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** Logger dieser Klasse. */
    private static final Logger LOG = LoggerFactory.getLogger(WebApiExceptionHandler.class);

    /**
     * Beantwortet ungültige Eingaben (etwa einen unbekannten Kraftstoff) mit HTTP&nbsp;400.
     *
     * @param ex die ausgelöste Ausnahme
     * @return Fehlerbeschreibung mit Status 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(final IllegalArgumentException ex) {
        LOG.debug("Ungültige Anfrage: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Beantwortet einen fehlgeschlagenen Datenbankzugriff (etwa bei DB-Ausfall) mit
     * HTTP&nbsp;503, statt den Fehler als HTTP&nbsp;500 durchschlagen zu lassen.
     *
     * @param ex die ausgelöste Datenzugriffsausnahme
     * @return Fehlerbeschreibung mit Status 503
     */
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail handleDatabaseUnavailable(final DataAccessException ex) {
        LOG.warn("Datenbankzugriff fehlgeschlagen: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Datenbank momentan nicht erreichbar.");
    }

    /**
     * Fängt alle übrigen, unerwarteten Ausnahmen ab, protokolliert sie mitsamt Stacktrace
     * auf ERROR-Ebene und beantwortet sie mit einer bereinigten HTTP&nbsp;500-Meldung ohne
     * interne Details.
     *
     * @param ex die unerwartete Ausnahme
     * @return Fehlerbeschreibung mit Status 500
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(final Exception ex) {
        LOG.error("Unerwarteter Fehler bei der Verarbeitung einer Web-API-Anfrage", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Interner Fehler bei der Verarbeitung der Anfrage.");
    }

}
