package de.lembergmax.tankermax.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice-Tests für {@link WebApiController} mit {@link MockMvc} und gemocktem Dienst – ohne
 * Datenbank. Prüfen Statuscode und Antwortform der Lese-Endpunkte, die Ablehnung unbekannter
 * Kraftstoffe (HTTP&nbsp;400) sowie das Setzen der Sicherheitsheader.
 */
@WebMvcTest(WebApiController.class)
@ActiveProfiles("web")
class WebApiControllerTest {

    /** Test-Client gegen den Web-Layer. */
    @Autowired
    private MockMvc mvc;

    /** Gemockter Tankstellen-/Preisdienst. */
    @MockitoBean
    private StationQueryService stationService;

    /** Gemockte Vorhersage-Konfiguration (für die {@code /meta}-Antwort). */
    @MockitoBean
    private ForecastProperties forecastProperties;

    /**
     * {@code GET /api/regions} liefert Status 200 und ein JSON-Array.
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void regionenLiefernArray() throws Exception {
        when(stationService.regions()).thenReturn(List.of());
        mvc.perform(get("/api/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    /**
     * Ein unbekannter Kraftstoff wird mit HTTP&nbsp;400 abgelehnt.
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void unbekannterKraftstoffErgibt400() throws Exception {
        mvc.perform(get("/api/stations").param("region", "Dresden").param("fuel", "super"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Antworten tragen die Content-Security-Policy als Sicherheitsheader.
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void antwortTraegtSicherheitsheader() throws Exception {
        when(stationService.regions()).thenReturn(List.of());
        mvc.perform(get("/api/regions"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    /**
     * Der Parameter {@code openOnly} wird gebunden und an den Dienst durchgereicht
     * (serverseitiges Ausblenden geschlossener Tankstellen).
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void openOnlyWirdAnDenDienstDurchgereicht() throws Exception {
        when(stationService.stations("Dresden", "DIESEL", true)).thenReturn(List.of());
        mvc.perform(get("/api/stations").param("region", "Dresden").param("fuel", "diesel").param("openOnly", "true"))
                .andExpect(status().isOk());
        verify(stationService).stations("Dresden", "DIESEL", true);
    }

    /**
     * Ein fehlgeschlagener Datenbankzugriff wird als HTTP&nbsp;503 beantwortet.
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void datenbankausfallErgibt503() throws Exception {
        when(stationService.regions()).thenThrow(new DataAccessResourceFailureException("DB weg"));
        mvc.perform(get("/api/regions"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Datenbank momentan nicht erreichbar."));
    }

    /**
     * Ein unerwarteter Fehler wird als bereinigte HTTP&nbsp;500-Meldung ohne interne Details
     * beantwortet.
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void unerwarteterFehlerErgibt500OhneInterneDetails() throws Exception {
        when(stationService.regions()).thenThrow(new RuntimeException("geheimer Stacktrace-Hinweis"));
        mvc.perform(get("/api/regions"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Interner Fehler bei der Verarbeitung der Anfrage."))
                .andExpect(content().string(not(containsString("geheimer Stacktrace-Hinweis"))));
    }

    /**
     * Ein fehlender Pflichtparameter ({@code region}) wird mit HTTP&nbsp;400 abgelehnt.
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void fehlendeRegionErgibt400() throws Exception {
        mvc.perform(get("/api/stations").param("fuel", "diesel"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Eine leere Region wird durch {@code @NotBlank} mit HTTP&nbsp;400 abgelehnt.
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void leereRegionErgibt400() throws Exception {
        mvc.perform(get("/api/stations").param("region", "").param("fuel", "diesel"))
                .andExpect(status().isBadRequest());
    }

    /**
     * {@code GET /api/meta} liefert Status 200 mit Zeitzone und Chart-Zeit.
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void metaLiefertZeitzone() throws Exception {
        mvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tz").value(ChartTime.ZONE_ID));
    }

    /**
     * {@code GET /api/history} liefert ohne Zeitbereich Status 200 und ein JSON-Array; die
     * Bereichsgrenzen werden dabei als {@code null} an den Dienst übergeben (Vorgabefenster).
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void historieLiefertArray() throws Exception {
        when(stationService.history("stat-1", "DIESEL", null, null)).thenReturn(List.of());
        mvc.perform(get("/api/history").param("station", "stat-1").param("fuel", "diesel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    /**
     * {@code GET /api/history} bindet {@code from}/{@code to} und reicht den Zeitbereich an den
     * Dienst durch (Nachladen älterer Abschnitte beim Zurückscrollen).
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void historieBereichWirdAnDenDienstDurchgereicht() throws Exception {
        when(stationService.history("stat-1", "DIESEL", 1000L, 2000L)).thenReturn(List.of());
        mvc.perform(get("/api/history")
                        .param("station", "stat-1").param("fuel", "diesel")
                        .param("from", "1000").param("to", "2000"))
                .andExpect(status().isOk());
        verify(stationService).history("stat-1", "DIESEL", 1000L, 2000L);
    }

}
