package de.lembergmax.tankermax.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit-Tests der Änderungserkennung in {@link LiveUpdateService} ohne Spring-Kontext. Prüfen, dass
 * die Lese-Caches nur bei wirklich neuen Beobachtungen geleert werden und ein Datenbankfehler die
 * Verbindungen unberührt lässt.
 */
class LiveUpdateServiceTest {

    /**
     * Der erste Lauf legt nur die Ausgangsversion fest; ein unveränderter Stand löst kein Leeren aus,
     * eine gestiegene Version leert beide Lese-Caches.
     */
    @Test
    void leertCachesNurBeiNeuenDaten() {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final CacheManager cacheManager = mock(CacheManager.class);
        final Cache stations = mock(Cache.class);
        final Cache regions = mock(Cache.class);
        when(cacheManager.getCache("stations")).thenReturn(stations);
        when(cacheManager.getCache("regions")).thenReturn(regions);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(5L, 5L, 7L);

        final LiveUpdateService service = new LiveUpdateService(jdbc, cacheManager, 0L);
        service.pulse();
        service.pulse();
        verify(stations, never()).clear();
        verify(regions, never()).clear();

        service.pulse();
        verify(stations).clear();
        verify(regions).clear();
    }

    /**
     * Ein Datenbankfehler bei der Änderungserkennung wird verschluckt und rührt die Caches nicht an.
     */
    @Test
    void datenbankfehlerLaesstCachesUnberuehrt() {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final CacheManager cacheManager = mock(CacheManager.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new DataAccessResourceFailureException("Datenbank weg"));

        final LiveUpdateService service = new LiveUpdateService(jdbc, cacheManager, 0L);
        service.pulse();

        verifyNoInteractions(cacheManager);
    }

}
