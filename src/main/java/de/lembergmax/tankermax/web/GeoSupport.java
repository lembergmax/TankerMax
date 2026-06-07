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

}
