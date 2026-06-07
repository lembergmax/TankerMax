package de.lembergmax.tankermax.web;

import de.lembergmax.tankermax.web.dto.MetaDto;
import de.lembergmax.tankermax.web.dto.PointDto;
import de.lembergmax.tankermax.web.dto.RegionDto;
import de.lembergmax.tankermax.web.dto.StationDto;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-Schnittstelle der Weboberfläche. Liefert ausschließlich gelesene Daten aus der
 * Datenbank; es werden keine Tankerkönig-API-Aufrufe ausgelöst. Nur im Profil {@code web} aktiv.
 *
 * <p>Die Längenbegrenzungen der Anfrageparameter und die Kraftstoffprüfung sind reine
 * Eingangsvalidierung; alle Datenbankzugriffe erfolgen ohnehin über gebundene Parameter.</p>
 */
@RestController
@RequestMapping("/api")
@Profile("web")
@Validated
@RequiredArgsConstructor
public class WebApiController {

    /** Höchstlänge der frei übergebenen Kennungen (Region, Tankstelle), als Schutz vor Missbrauch. */
    private static final int MAX_IDENTIFIER_LENGTH = 64;

    /** Zugriff auf Tankstellen, Preise und Historie. */
    private final StationQueryService stationService;

    /**
     * Liefert allgemeine Metadaten (Chart-Zeit und Zeitzone).
     *
     * @return Metadaten mit Chart-Zeit und Zeitzone
     */
    @GetMapping("/meta")
    public MetaDto meta() {
        return new MetaDto(ChartTime.now(), ChartTime.ZONE_ID);
    }

    /**
     * Liefert die wählbaren Regionen.
     *
     * @return Liste der Regionen
     */
    @GetMapping("/regions")
    public List<RegionDto> regions() {
        return stationService.regions();
    }

    /**
     * Liefert die Tankstellen einer Region für einen Kraftstoff.
     *
     * @param region   Kennung der Region
     * @param fuel     Frontend-Kennung des Kraftstoffs (e5/e10/diesel)
     * @param openOnly {@code true}, um geschlossene Tankstellen auszublenden (nur geöffnete zurückgeben)
     * @return Liste der Tankstellen
     */
    @GetMapping("/stations")
    public List<StationDto> stations(@RequestParam @Size(max = MAX_IDENTIFIER_LENGTH) final String region,
                                     @RequestParam final String fuel,
                                     @RequestParam(defaultValue = "false") final boolean openOnly) {
        return stationService.stations(region, requireKnownFuel(fuel), openOnly);
    }

    /**
     * Liefert die Ist-Historie einer Tankstelle.
     *
     * @param station Kennung der Tankstelle
     * @param fuel    Frontend-Kennung des Kraftstoffs
     * @return Ist-Preiskurve
     */
    @GetMapping("/history")
    public List<PointDto> history(@RequestParam @Size(max = MAX_IDENTIFIER_LENGTH) final String station,
                                  @RequestParam final String fuel) {
        return stationService.history(station, requireKnownFuel(fuel));
    }

    /**
     * Prüft die Frontend-Kraftstoffkennung und liefert den zugehörigen Datenbank-Code.
     *
     * @param fuel Frontend-Kennung (e5/e10/diesel)
     * @return Datenbank-Code des Kraftstoffs
     * @throws IllegalArgumentException wenn der Kraftstoff unbekannt ist (führt zu HTTP 400)
     */
    private static String requireKnownFuel(final String fuel) {
        if (!FuelCodes.isKnown(fuel)) {
            throw new IllegalArgumentException("Unbekannter Kraftstoff: " + fuel);
        }
        return FuelCodes.toDb(fuel);
    }

}
