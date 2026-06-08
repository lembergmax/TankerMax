package de.lembergmax.tankermax.web;

import lombok.experimental.UtilityClass;

/**
 * Geografische Hilfsfunktionen (Entfernungsberechnung).
 */
@UtilityClass
public class GeoSupport {

    /** Mittlerer Erdradius in Kilometern. */
    private final double EARTH_RADIUS_KM = 6371.0;

    /** Faktor zur Rundung der Entfernung auf eine Nachkommastelle. */
    private final double ONE_DECIMAL = 10.0;

    /** Ungefähre Strecke in Kilometern, die einem Grad geografischer Breite entspricht. */
    private final double KM_PER_DEGREE_LATITUDE = 111.0;

    /**
     * Berechnet die Luftlinien-Entfernung zweier Punkte nach der Haversine-Formel.
     *
     * @param lat1 Breite des ersten Punkts
     * @param lon1 Länge des ersten Punkts
     * @param lat2 Breite des zweiten Punkts
     * @param lon2 Länge des zweiten Punkts
     * @return Entfernung in Kilometern, auf eine Nachkommastelle gerundet
     */
    public double distanceKm(final double lat1, final double lon1, final double lat2, final double lon2) {
        final double dLat = Math.toRadians(lat2 - lat1);
        final double dLon = Math.toRadians(lon2 - lon1);
        final double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        final double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Math.round(EARTH_RADIUS_KM * c * ONE_DECIMAL) / ONE_DECIMAL;
    }

    /**
     * Berechnet das umschließende Rechteck (Bounding-Box) um einen Mittelpunkt für einen gegebenen
     * Radius. Es dient als grober, indexierbarer Vorfilter in SQL ({@code lat/lng BETWEEN …}); die
     * genaue Kreisprüfung übernimmt anschließend {@link #distanceKm}.
     *
     * <p>Da ein Längengrad mit zunehmender Breite kürzer wird, wird die Längenausdehnung durch den
     * Kosinus der Breite geteilt. Das Rechteck umschließt den Kreis vollständig und liefert daher
     * keine falschen Auslassungen, sondern allenfalls einige Punkte in den Ecken, die der
     * Feinfilter verwirft.</p>
     *
     * @param lat      Breite des Mittelpunkts
     * @param lng      Länge des Mittelpunkts
     * @param radiusKm Suchradius in Kilometern
     * @return umschließendes Rechteck mit minimaler/maximaler Breite und Länge
     */
    public BoundingBox boundingBox(final double lat, final double lng, final double radiusKm) {
        final double latDelta = radiusKm / KM_PER_DEGREE_LATITUDE;
        final double cosLat = Math.cos(Math.toRadians(lat));
        final double lngDelta = cosLat <= 0 ? 180.0 : radiusKm / (KM_PER_DEGREE_LATITUDE * cosLat);
        return new BoundingBox(lat - latDelta, lat + latDelta, lng - lngDelta, lng + lngDelta);
    }

    /**
     * Umschließendes geografisches Rechteck als Vorfilter für die Radiussuche.
     *
     * @param latMin minimale Breite
     * @param latMax maximale Breite
     * @param lngMin minimale Länge
     * @param lngMax maximale Länge
     */
    public record BoundingBox(double latMin, double latMax, double lngMin, double lngMax) {

    }

}
