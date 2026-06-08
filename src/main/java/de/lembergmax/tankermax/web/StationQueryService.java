package de.lembergmax.tankermax.web;

import de.lembergmax.tankermax.polling.config.Location;
import de.lembergmax.tankermax.polling.config.TankerkoenigProperties;
import de.lembergmax.tankermax.polling.domain.ObservationStatus;
import de.lembergmax.tankermax.web.dto.OpeningTimeDto;
import de.lembergmax.tankermax.web.dto.PointDto;
import de.lembergmax.tankermax.web.dto.RegionDto;
import de.lembergmax.tankermax.web.GeoSupport.BoundingBox;
import de.lembergmax.tankermax.web.dto.StationDto;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest Tankstellen-Stammdaten, aktuelle Preise und die Preis-Historie aus der
 * Datenbank {@code tankermax}.
 *
 * <p>Die Zugehörigkeit einer Tankstelle zu einer Region wird geografisch bestimmt: Eine
 * Tankstelle gehört zu einer Region, wenn sie innerhalb des konfigurierten Suchradius um den
 * Ortsmittelpunkt liegt. Dadurch liefert jede Region nur ihre eigenen Tankstellen, auch wenn
 * mehrere Orte konfiguriert sind.</p>
 */
@Service
@Profile("web")
@RequiredArgsConstructor
public class StationQueryService {

    /** Anzahl der Tage, die für die Ist-Historie geladen werden. */
    private static final int HISTORY_DAYS = 14;

    /**
     * Zeitfenster in Tagen, innerhalb dessen der jüngste Preis je Tankstelle gesucht wird. Der
     * aktuelle Preis stammt ohnehin aus dem letzten Abfragezyklus; das Fenster begrenzt den Scan der
     * mit jedem Pollzyklus wachsenden Beobachtungstabelle auf einen festen Ausschnitt, statt die
     * gesamte Historie zu durchsuchen. Großzügig genug, um auch bei vielen Orten und Pausen den
     * letzten Preis sicher zu erfassen.
     */
    private static final int LATEST_PRICE_LOOKBACK_DAYS = 7;

    /** Format der an das Frontend gelieferten Öffnungs- und Schließzeiten ({@code HH:mm}). */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** Statuswert einer geöffneten Beobachtung, aus dem Enum abgeleitet (statt String-Literal in SQL). */
    private static final String OPEN_STATUS = ObservationStatus.OPEN.name();

    /** Zugriff auf die Datenbank. */
    private final JdbcTemplate jdbc;

    /** Konfiguration mit den abgefragten Orten. */
    private final TankerkoenigProperties properties;

    /**
     * Liefert die wählbaren Regionen (konfigurierte Orte) mit der Anzahl der Tankstellen im Radius.
     *
     * <p>Das Ergebnis wird kurzzeitig zwischengespeichert, da es sich nur im Poll-Takt ändert.</p>
     *
     * @return Liste der Regionen mit ortsbezogener Tankstellenzahl
     */
    @Cacheable("regions")
    public List<RegionDto> regions() {
        final List<RegionDto> regions = new ArrayList<>();
        for (final Location location : properties.getLocations()) {
            regions.add(new RegionDto(location.getName(), location.getName(), countWithin(location)));
        }
        return regions;
    }

    /**
     * Liefert die Tankstellen einer Region mit aktuellem Preis.
     *
     * <p>Es werden nur Tankstellen innerhalb des Suchradius um den Ortsmittelpunkt zurückgegeben.
     * Eine unbekannte Region liefert eine leere Liste.</p>
     *
     * <p>Das Ergebnis wird je Kombination aus Region, Kraftstoff und {@code openOnly} kurzzeitig
     * zwischengespeichert, da es sich nur im Poll-Takt ändert.</p>
     *
     * @param region   Kennung der Region
     * @param fuelDb   Datenbank-Code des Kraftstoffs
     * @param openOnly {@code true}, um nur geöffnete Tankstellen zurückzugeben (geschlossene werden ausgeblendet)
     * @return Liste der Tankstellen der Region
     */
    @Cacheable("stations")
    public List<StationDto> stations(final String region, final String fuelDb, final boolean openOnly) {
        final Location center = centerFor(region);
        if (center == null) {
            return List.of();
        }
        final BoundingBox box = GeoSupport.boundingBox(center.getLatitude(), center.getLongitude(), center.getRadiusKm());
        final Map<String, Double> prices = latestOpenPrices(fuelDb, box);
        final List<Selected> selected = new ArrayList<>();
        for (final Base base : loadStations(box)) {
            final double dist = GeoSupport.distanceKm(center.getLatitude(), center.getLongitude(), base.lat(), base.lng());
            if (dist > center.getRadiusKm()) {
                continue;
            }
            final Double price = prices.get(base.id());
            final boolean open = price != null;
            if (openOnly && !open) {
                continue;
            }
            selected.add(new Selected(base, dist, open, price));
        }
        final Map<String, List<OpeningTimeDto>> openingTimes =
                loadOpeningTimes(selected.stream().map(item -> item.base().id()).toList());
        final List<StationDto> stations = new ArrayList<>();
        for (final Selected item : selected) {
            final Base base = item.base();
            stations.add(new StationDto(base.id(), base.brand(), base.name(),
                    base.street() == null ? "" : base.street(), base.postCode(), base.place(),
                    base.lat(), base.lng(), item.dist(), item.open(), base.wholeDay(), item.price(),
                    openingTimes.getOrDefault(base.id(), List.of())));
        }
        return stations;
    }

    /**
     * Liefert die Ist-Preis-Historie einer Tankstelle der letzten Tage.
     *
     * @param stationId Kennung der Tankstelle
     * @param fuelDb    Datenbank-Code des Kraftstoffs
     * @return zeitlich sortierte Preispunkte (Euro/Liter)
     */
    public List<PointDto> history(final String stationId, final String fuelDb) {
        final LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(HISTORY_DAYS, ChronoUnit.DAYS);
        return jdbc.query(
                "SELECT po.observed_at AS observed_at, fp.amount AS amount FROM price_observation po "
                        + "JOIN fuel_price fp ON fp.observation_id = po.id "
                        + "JOIN fuel_type ft ON ft.id = fp.fuel_type_id "
                        + "WHERE po.station_id = ? AND ft.code = ? AND po.status = ? "
                        + "AND po.observed_at >= ? ORDER BY po.observed_at",
                (resultSet, row) -> new PointDto(
                        ChartTime.fromUtc(resultSet.getObject("observed_at", LocalDateTime.class)),
                        resultSet.getBigDecimal("amount").doubleValue()),
                stationId, fuelDb, OPEN_STATUS, cutoff);
    }

    /**
     * Zählt die Tankstellen, die im Suchradius um den Ortsmittelpunkt liegen.
     *
     * <p>Vorgefiltert wird in SQL über die Bounding-Box des Ortes, sodass nur die Koordinaten der
     * nahe gelegenen Tankstellen geladen werden; die genaue Kreisprüfung übernimmt anschließend die
     * Haversine-Entfernung.</p>
     *
     * @param location Ort mit Mittelpunkt und Radius
     * @return Anzahl der Tankstellen innerhalb des Radius
     */
    private int countWithin(final Location location) {
        final BoundingBox box = GeoSupport.boundingBox(location.getLatitude(), location.getLongitude(), location.getRadiusKm());
        final List<Coord> coords = jdbc.query(
                "SELECT latitude AS lat, longitude AS lng FROM station "
                        + "WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?",
                (resultSet, row) -> new Coord(resultSet.getDouble("lat"), resultSet.getDouble("lng")),
                box.latMin(), box.latMax(), box.lngMin(), box.lngMax());
        int count = 0;
        for (final Coord coord : coords) {
            if (GeoSupport.distanceKm(location.getLatitude(), location.getLongitude(),
                    coord.lat(), coord.lng()) <= location.getRadiusKm()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Lädt die Stammdaten der Tankstellen innerhalb der Bounding-Box.
     *
     * @param box umschließendes Rechteck als Vorfilter
     * @return Liste der Stammdatensätze innerhalb des Rechtecks
     */
    private List<Base> loadStations(final BoundingBox box) {
        return jdbc.query(
                "SELECT s.id AS id, COALESCE(b.name, '') AS brand, s.name AS name, s.street AS street, "
                        + "s.post_code AS post_code, s.place AS place, s.latitude AS lat, s.longitude AS lng, "
                        + "s.whole_day AS whole_day FROM station s LEFT JOIN brand b ON b.id = s.brand_id "
                        + "WHERE s.latitude BETWEEN ? AND ? AND s.longitude BETWEEN ? AND ?",
                (resultSet, row) -> new Base(
                        resultSet.getString("id"), resultSet.getString("brand"), resultSet.getString("name"),
                        resultSet.getString("street"), resultSet.getString("post_code"), resultSet.getString("place"),
                        resultSet.getDouble("lat"), resultSet.getDouble("lng"), resultSet.getBoolean("whole_day")),
                box.latMin(), box.latMax(), box.lngMin(), box.lngMax());
    }

    /**
     * Lädt die regulären Öffnungszeiten der angegebenen Tankstellen, gruppiert je Tankstelle.
     *
     * <p>Geladen werden nur die Öffnungszeiten der tatsächlich zurückgegebenen Tankstellen (statt
     * aller angereicherten), und nur für solche mit gesetztem {@code details_fetched_at}; für alle
     * übrigen fehlt der Eintrag, sodass das Frontend auf das {@code wholeDay}-Kennzeichen zurückfällt.
     * Die Zeiten werden bereits hier nach {@code HH:mm} formatiert, damit das Frontend sie ohne
     * weitere Umwandlung anzeigen kann.</p>
     *
     * @param stationIds Kennungen der Tankstellen, deren Öffnungszeiten geladen werden
     * @return Abbildung Tankstellen-Kennung auf ihre Öffnungszeiten in Reihenfolge der Öffnungszeit
     */
    private Map<String, List<OpeningTimeDto>> loadOpeningTimes(final List<String> stationIds) {
        final Map<String, List<OpeningTimeDto>> byStation = new HashMap<>();
        if (stationIds.isEmpty()) {
            return byStation;
        }
        final String placeholders = String.join(", ", Collections.nCopies(stationIds.size(), "?"));
        jdbc.query(
                "SELECT ot.station_id AS station_id, ot.description AS description, "
                        + "ot.start_time AS start_time, ot.end_time AS end_time FROM opening_time ot "
                        + "JOIN station s ON s.id = ot.station_id "
                        + "WHERE s.details_fetched_at IS NOT NULL AND ot.station_id IN (" + placeholders + ") "
                        + "ORDER BY ot.station_id, ot.start_time",
                resultSet -> {
                    byStation.computeIfAbsent(resultSet.getString("station_id"), key -> new ArrayList<>())
                            .add(new OpeningTimeDto(resultSet.getString("description"),
                                    formatTime(resultSet.getObject("start_time", LocalTime.class)),
                                    formatTime(resultSet.getObject("end_time", LocalTime.class))));
                },
                stationIds.toArray());
        return byStation;
    }

    /**
     * Formatiert eine Uhrzeit nach {@code HH:mm}.
     *
     * @param time Uhrzeit oder {@code null}
     * @return formatierte Uhrzeit oder {@code null}, wenn keine Uhrzeit vorliegt
     */
    private static String formatTime(final LocalTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    /**
     * Ermittelt je Tankstelle innerhalb der Bounding-Box den jüngsten offenen Preis eines
     * Kraftstoffs.
     *
     * <p>Die Abfrage ist mehrfach eingegrenzt – auf die Kraftstoffart, den geöffneten Status, das
     * Zeitfenster der letzten Tage und die Bounding-Box der Region –, sodass die Fensterfunktion
     * ({@code ROW_NUMBER}) nur einen kleinen, indexgestützten Ausschnitt der stetig wachsenden
     * Beobachtungstabelle verarbeitet, statt deren gesamte Historie zu durchsuchen.</p>
     *
     * @param fuelDb Datenbank-Code des Kraftstoffs
     * @param box    umschließendes Rechteck der Region als Vorfilter
     * @return Abbildung Tankstellen-Kennung auf Preis (Euro/Liter)
     */
    private Map<String, Double> latestOpenPrices(final String fuelDb, final BoundingBox box) {
        final LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(LATEST_PRICE_LOOKBACK_DAYS, ChronoUnit.DAYS);
        final Map<String, Double> prices = new HashMap<>();
        jdbc.query(
                "SELECT station_id, amount FROM (SELECT po.station_id AS station_id, fp.amount AS amount, "
                        + "ROW_NUMBER() OVER (PARTITION BY po.station_id ORDER BY po.observed_at DESC) rn "
                        + "FROM price_observation po JOIN fuel_price fp ON fp.observation_id = po.id "
                        + "JOIN fuel_type ft ON ft.id = fp.fuel_type_id "
                        + "JOIN station s ON s.id = po.station_id "
                        + "WHERE ft.code = ? AND po.status = ? AND po.observed_at >= ? "
                        + "AND s.latitude BETWEEN ? AND ? AND s.longitude BETWEEN ? AND ?) t WHERE rn = 1",
                resultSet -> {
                    prices.put(resultSet.getString("station_id"),
                            resultSet.getBigDecimal("amount").doubleValue());
                },
                fuelDb, OPEN_STATUS, cutoff, box.latMin(), box.latMax(), box.lngMin(), box.lngMax());
        return prices;
    }

    /**
     * Liefert den Ortsmittelpunkt einer Region aus der Konfiguration.
     *
     * @param region Kennung der Region
     * @return zugehöriger Ort oder {@code null}, wenn die Region unbekannt ist
     */
    private Location centerFor(final String region) {
        return properties.getLocations().stream()
                .filter(location -> location.getName().equals(region))
                .findFirst()
                .orElse(null);
    }

    /**
     * Koordinaten einer Tankstelle für die geografische Regions-Zuordnung.
     *
     * @param lat Breitengrad
     * @param lng Längengrad
     */
    private record Coord(double lat, double lng) {

    }

    /**
     * Stammdaten einer Tankstelle aus der Datenbank.
     *
     * @param id       Kennung
     * @param brand    Marke
     * @param name     Name
     * @param street   Straße
     * @param postCode Postleitzahl
     * @param place    Ort
     * @param lat      Breite
     * @param lng      Länge
     * @param wholeDay rund um die Uhr geöffnet
     */
    private record Base(String id, String brand, String name, String street, String postCode,
                        String place, double lat, double lng, boolean wholeDay) {

    }

    /**
     * Eine innerhalb des Radius ausgewählte Tankstelle samt berechneter Entfernung, Status und Preis,
     * bevor die Öffnungszeiten ergänzt werden.
     *
     * @param base  Stammdaten der Tankstelle
     * @param dist  Entfernung zum Ortsmittelpunkt in Kilometern
     * @param open  {@code true}, wenn ein aktueller Preis vorliegt (geöffnet)
     * @param price aktueller Preis (Euro/Liter) oder {@code null}
     */
    private record Selected(Base base, double dist, boolean open, Double price) {

    }

}
