package de.lembergmax.tankermax.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Setzt grundlegende Sicherheits-Antwortheader für das Dashboard und die Web-API.
 *
 * <p>Da keine Spring Security aktiv ist, werden die Header hier zentral ergänzt. Die
 * Content-Security-Policy ist eng gefasst: Skripte, Schriftarten und Verbindungen nur von
 * der eigenen Herkunft. Inline-Styles bleiben erlaubt ({@code style-src 'unsafe-inline'}),
 * da das dynamisch erzeugte Markup durchgängig {@code style="…"}-Attribute verwendet; das
 * Theme-Skript ist hingegen ausgelagert, sodass {@code script-src 'self'} genügt. Nur im
 * Profil {@code web} aktiv.</p>
 */
@Component
@Profile("web")
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /** Content-Security-Policy für das statische Dashboard und die gleiche Herkunft nutzende API. */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    /**
     * Ergänzt jede Antwort um die Sicherheitsheader und setzt die Filterkette fort.
     *
     * @param request     eingehende Anfrage
     * @param response    ausgehende Antwort
     * @param filterChain weitere Filterkette
     * @throws ServletException bei einem Servlet-Fehler in der Kette
     * @throws IOException      bei einem E/A-Fehler in der Kette
     */
    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        filterChain.doFilter(request, response);
    }

}
