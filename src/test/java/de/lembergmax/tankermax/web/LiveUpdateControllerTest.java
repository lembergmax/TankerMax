package de.lembergmax.tankermax.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Slice-Tests für {@link LiveUpdateController} mit {@link MockMvc} und gemocktem Dienst. Prüfen, dass
 * der Echtzeit-Endpunkt eine asynchrone {@code text/event-stream}-Antwort öffnet und die Kopfzeilen
 * gegen Zwischenspeicherung und Proxy-Pufferung setzt.
 */
@WebMvcTest(LiveUpdateController.class)
@ActiveProfiles("web")
class LiveUpdateControllerTest {

    /** Test-Client gegen den Web-Layer. */
    @Autowired
    private MockMvc mvc;

    /** Gemockter Echtzeit-Dienst. */
    @MockitoBean
    private LiveUpdateService liveUpdateService;

    /**
     * {@code GET /api/stream} startet eine asynchrone Antwort, liefert {@code text/event-stream} und
     * setzt die Kopfzeilen gegen Zwischenspeicherung und Proxy-Pufferung.
     *
     * @throws Exception wenn die Anfrage fehlschlägt
     */
    @Test
    void streamOeffnetEventStreamOhneCaching() throws Exception {
        final SseEmitter emitter = new SseEmitter(0L);
        when(liveUpdateService.subscribe()).thenReturn(emitter);

        final MvcResult result = mvc.perform(get("/api/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        emitter.complete();

        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(header().string("X-Accel-Buffering", "no"));
    }

}
