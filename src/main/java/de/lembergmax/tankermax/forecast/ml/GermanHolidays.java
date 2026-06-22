package de.lembergmax.tankermax.forecast.ml;

import lombok.experimental.UtilityClass;

import java.time.LocalDate;
import java.util.Set;

/**
 * Ermittelt gesetzliche Feiertage in Deutschland – bundesweit sowie für die häufigsten
 * landesspezifischen Feiertage.
 *
 * <p>Bewusst als kleine, eigenständige Hilfsklasse umgesetzt, um auf dem Raspberry Pi keine weitere
 * Bibliothek einzubinden. Die beweglichen Feiertage werden über das Osterdatum (Gauß-/Butcher-Formel)
 * berechnet, die landesspezifischen anhand des in den Stammdaten geführten Bundeslandes. Ist das
 * Bundesland unbekannt, werden nur die bundesweiten Feiertage berücksichtigt.</p>
 */
@UtilityClass
public class GermanHolidays {

    /** Bundesländer mit Heilige Drei Könige (6. Januar). */
    private final Set<String> EPIPHANY = Set.of("BW", "BY", "ST");

    /** Bundesländer mit Fronleichnam (Ostern + 60 Tage). */
    private final Set<String> CORPUS_CHRISTI = Set.of("BW", "BY", "HE", "NW", "RP", "SL");

    /** Bundesländer mit Allerheiligen (1. November). */
    private final Set<String> ALL_SAINTS = Set.of("BW", "BY", "NW", "RP", "SL");

    /** Bundesländer mit Reformationstag (31. Oktober). */
    private final Set<String> REFORMATION = Set.of("BB", "MV", "SN", "ST", "TH", "HB", "HH", "NI", "SH");

    /** Bundesländer mit Mariä Himmelfahrt (15. August); im Saarland durchgängig. */
    private final Set<String> ASSUMPTION = Set.of("SL");

    /** Bundesländer mit Internationalem Frauentag (8. März). */
    private final Set<String> WOMENS_DAY = Set.of("BE", "MV");

    /**
     * Prüft, ob das Datum im angegebenen Bundesland ein gesetzlicher Feiertag ist.
     *
     * @param date  zu prüfendes Datum
     * @param state Bundesland (Name oder Kürzel) oder {@code null} für nur bundesweite Feiertage
     * @return {@code true}, wenn es ein Feiertag ist
     */
    public boolean isHoliday(final LocalDate date, final String state) {
        if (date == null) {
            return false;
        }
        final LocalDate easter = easterSunday(date.getYear());
        if (isNationwide(date, easter)) {
            return true;
        }
        final String code = normalizeState(state);
        if (code == null) {
            return false;
        }
        return isRegional(date, easter, code);
    }

    /**
     * Prüft die bundesweiten Feiertage (in allen Bundesländern gültig).
     *
     * @param date   zu prüfendes Datum
     * @param easter Ostersonntag des Jahres
     * @return {@code true}, wenn es ein bundesweiter Feiertag ist
     */
    private boolean isNationwide(final LocalDate date, final LocalDate easter) {
        return isFixed(date, 1, 1)            // Neujahr
                || isFixed(date, 5, 1)        // Tag der Arbeit
                || isFixed(date, 10, 3)       // Tag der Deutschen Einheit
                || isFixed(date, 12, 25)      // 1. Weihnachtstag
                || isFixed(date, 12, 26)      // 2. Weihnachtstag
                || date.equals(easter.minusDays(2))   // Karfreitag
                || date.equals(easter.plusDays(1))    // Ostermontag
                || date.equals(easter.plusDays(39))   // Christi Himmelfahrt
                || date.equals(easter.plusDays(50));  // Pfingstmontag
    }

    /**
     * Prüft die landesspezifischen Feiertage des angegebenen Bundeslandes.
     *
     * @param date   zu prüfendes Datum
     * @param easter Ostersonntag des Jahres
     * @param code   normalisiertes Bundesland-Kürzel
     * @return {@code true}, wenn es im Bundesland ein Feiertag ist
     */
    private boolean isRegional(final LocalDate date, final LocalDate easter, final String code) {
        if (isFixed(date, 1, 6) && EPIPHANY.contains(code)) {
            return true;
        }
        if (isFixed(date, 3, 8) && WOMENS_DAY.contains(code)) {
            return true;
        }
        if (date.equals(easter.plusDays(60)) && CORPUS_CHRISTI.contains(code)) {
            return true;
        }
        if (isFixed(date, 8, 15) && ASSUMPTION.contains(code)) {
            return true;
        }
        if (isFixed(date, 9, 20) && "TH".equals(code)) {
            return true;
        }
        if (isFixed(date, 10, 31) && REFORMATION.contains(code)) {
            return true;
        }
        if (isFixed(date, 11, 1) && ALL_SAINTS.contains(code)) {
            return true;
        }
        return isPenanceDay(date) && "SN".equals(code);
    }

    /**
     * Prüft, ob das Datum auf einen festen Monatstag fällt.
     *
     * @param date  zu prüfendes Datum
     * @param month Monat (1–12)
     * @param day   Tag im Monat
     * @return {@code true}, wenn Monat und Tag übereinstimmen
     */
    private boolean isFixed(final LocalDate date, final int month, final int day) {
        return date.getMonthValue() == month && date.getDayOfMonth() == day;
    }

    /**
     * Prüft, ob das Datum der Buß- und Bettag ist (Mittwoch vor dem 23. November).
     *
     * @param date zu prüfendes Datum
     * @return {@code true}, wenn es der Buß- und Bettag ist
     */
    private boolean isPenanceDay(final LocalDate date) {
        if (date.getMonthValue() != 11) {
            return false;
        }
        final int day = date.getDayOfMonth();
        return day >= 16 && day <= 22 && date.getDayOfWeek().getValue() == 3;
    }

    /**
     * Berechnet den Ostersonntag eines Jahres nach der anonymen gregorianischen Formel.
     *
     * @param year Jahr
     * @return Datum des Ostersonntags
     */
    private LocalDate easterSunday(final int year) {
        final int a = year % 19;
        final int b = year / 100;
        final int c = year % 100;
        final int d = b / 4;
        final int e = b % 4;
        final int f = (b + 8) / 25;
        final int g = (b - f + 1) / 3;
        final int h = (19 * a + b - d - g + 15) % 30;
        final int i = c / 4;
        final int k = c % 4;
        final int l = (32 + 2 * e + 2 * i - h - k) % 7;
        final int m = (a + 11 * h + 22 * l) / 451;
        final int month = (h + l - 7 * m + 114) / 31;
        final int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    /**
     * Bildet eine Bundesland-Angabe (vollständiger Name oder Kürzel) auf das zweistellige Kürzel ab.
     *
     * @param state Bundesland-Angabe oder {@code null}
     * @return zweistelliges Kürzel oder {@code null}, wenn nicht zuordenbar
     */
    private String normalizeState(final String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        final String value = state.trim().toLowerCase();
        return switch (value) {
            case "bw", "baden-württemberg", "baden-wuerttemberg" -> "BW";
            case "by", "bayern", "freistaat bayern" -> "BY";
            case "be", "berlin" -> "BE";
            case "bb", "brandenburg" -> "BB";
            case "hb", "bremen" -> "HB";
            case "hh", "hamburg" -> "HH";
            case "he", "hessen" -> "HE";
            case "mv", "mecklenburg-vorpommern" -> "MV";
            case "ni", "niedersachsen" -> "NI";
            case "nw", "nrw", "nordrhein-westfalen" -> "NW";
            case "rp", "rheinland-pfalz" -> "RP";
            case "sl", "saarland" -> "SL";
            case "sn", "sachsen", "freistaat sachsen" -> "SN";
            case "st", "sachsen-anhalt" -> "ST";
            case "sh", "schleswig-holstein" -> "SH";
            case "th", "thüringen", "thueringen", "freistaat thüringen" -> "TH";
            default -> null;
        };
    }

}
