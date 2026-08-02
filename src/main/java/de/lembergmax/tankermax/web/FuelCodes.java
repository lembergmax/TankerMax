package de.lembergmax.tankermax.web;

import lombok.experimental.UtilityClass;

import java.util.Set;

/**
 * Wandelt die Kraftstoff-Kennungen des Frontends in die Datenbank-Codes um.
 */
@UtilityClass
public class FuelCodes {

    /** Bekannte Frontend-Kennungen in Kleinschreibung. */
    private final Set<String> KNOWN = Set.of("e5", "e10", "diesel");

    /**
     * Bildet eine Frontend-Kennung (zum Beispiel {@code e5}) auf den DB-Code (zum Beispiel {@code E5}) ab.
     *
     * @param fuel Frontend-Kennung
     * @return Datenbank-Code in Großbuchstaben
     */
    public String toDb(final String fuel) {
        final String value = fuel == null ? "" : fuel.toLowerCase();
        return switch (value) {
            case "e5" -> "E5";
            case "e10" -> "E10";
            case "diesel" -> "DIESEL";
            default -> value.toUpperCase();
        };
    }

    /**
     * Prüft, ob die Frontend-Kennung einem der unterstützten Kraftstoffe entspricht.
     *
     * @param fuel Frontend-Kennung
     * @return {@code true}, wenn der Kraftstoff bekannt ist (E5, E10 oder Diesel)
     */
    public boolean isKnown(final String fuel) {
        return fuel != null && KNOWN.contains(fuel.toLowerCase());
    }

}
