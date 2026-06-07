package de.lembergmax.tankermax;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Stellt beim Anwendungsstart sicher, dass ein sinnvolles Betriebsprofil aktiv ist.
 *
 * <p>Sämtliche fachlichen Beans sind über {@code @Profile("ingest")} bzw.
 * {@code @Profile("web")} gebunden; ohne eines dieser Profile würde die Anwendung
 * sauber, aber wirkungslos starten (kein Scheduler, keine Web-API). Diese Prüfung bricht
 * einen solchen Fehlstart früh mit einer eindeutigen Meldung ab.</p>
 */
@Component
public class ActiveProfileGuard {

    /** Profile, von denen mindestens eines aktiv sein muss. */
    private static final List<String> REQUIRED_PROFILES = List.of("ingest", "web");

    /**
     * Prüft die aktiven Profile und bricht den Start bei fehlendem Betriebsprofil ab.
     *
     * @param environment Spring-Umgebung mit den aktiven Profilen
     * @throws IllegalStateException wenn weder {@code ingest} noch {@code web} aktiv ist
     */
    public ActiveProfileGuard(final Environment environment) {
        final List<String> active = Arrays.asList(environment.getActiveProfiles());
        final boolean hasOperatingProfile = REQUIRED_PROFILES.stream().anyMatch(active::contains);
        if (!hasOperatingProfile) {
            throw new IllegalStateException(
                    "Kein Betriebsprofil aktiv. Es muss mindestens eines von 'ingest' oder 'web' "
                            + "gesetzt sein, z. B. --spring.profiles.active=web,ingest.");
        }
    }

}
