package de.lembergmax.tankermax.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.lembergmax.tankermax.web.dto.ForecastResponseDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice-Tests für {@link ForecastApiController} mit {@link MockMvc} und gemocktem Dienst – ohne
 * Datenbank. Prüfen Statuscode und Antwortform des Vorhersage-Endpunkts sowie die Ablehnung eines
 * unbekannten Kraftstoffs (HTTP&nbsp;400).
 */
@WebMvcTest(ForecastApiController.class)
@ActiveProfiles("web")
class ForecastApiControllerTest {

    /** Test-Client gegen den Web-Layer. */
    @Autowired
    private MockMvc mvc;

    /** Gemockter Vorhersagedienst. */
    @MockitoBean
    private ForecastQueryService forecastService;

    /**
     * {@code GET /api/forecast} liefert Status 200 und reicht den DB-Kraftstoffcode an den Dienst durch.
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void vorhersageLiefertObjekt() throws Exception {
        when(forecastService.forecast("stat-1", "DIESEL"))
                .thenReturn(new ForecastResponseDto(false, 0, List.of(), List.of(), List.of(), null, null));
        mvc.perform(get("/api/forecast").param("station", "stat-1").param("fuel", "diesel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
        verify(forecastService).forecast("stat-1", "DIESEL");
    }

    /**
     * Ein unbekannter Kraftstoff wird mit HTTP&nbsp;400 abgelehnt.
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void unbekannterKraftstoffErgibt400() throws Exception {
        mvc.perform(get("/api/forecast").param("station", "stat-1").param("fuel", "super"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Eine fehlende Tankstellen-Kennung wird mit HTTP&nbsp;400 abgelehnt.
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void fehlendeStationErgibt400() throws Exception {
        mvc.perform(get("/api/forecast").param("fuel", "diesel"))
                .andExpect(status().isBadRequest());
    }

}
