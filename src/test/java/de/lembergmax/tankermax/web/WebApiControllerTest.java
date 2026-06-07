package de.lembergmax.tankermax.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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

}
