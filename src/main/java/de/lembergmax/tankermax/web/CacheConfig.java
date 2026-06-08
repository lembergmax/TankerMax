package de.lembergmax.tankermax.web;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * Aktiviert das serverseitige Caching der Lese-Endpunkte und konfiguriert eine kurze Lebensdauer.
 *
 * <p>Die Tankstellen- und Regionsdaten ändern sich nur im Poll-Takt; ein kurzlebiger Cache entlastet
 * daher die teureren Datenbankabfragen, ohne je veraltete Preise auszuliefern, solange die
 * Lebensdauer deutlich unter dem Abfrageabstand liegt. Nur im Profil {@code web} aktiv.</p>
 */
@Configuration
@Profile("web")
@EnableCaching
public class CacheConfig {

    /** Lebensdauer der zwischengespeicherten Einträge; bewusst kürzer als der Poll-Abstand. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /**
     * Erzeugt den Cache-Manager mit Caffeine als Anbieter und der konfigurierten Lebensdauer.
     *
     * @return Cache-Manager für die Lese-Endpunkte
     */
    @Bean
    public CacheManager cacheManager() {
        final CaffeineCacheManager cacheManager = new CaffeineCacheManager("regions", "stations");
        cacheManager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(CACHE_TTL));
        return cacheManager;
    }

}
