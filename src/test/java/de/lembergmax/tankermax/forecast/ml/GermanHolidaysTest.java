package de.lembergmax.tankermax.forecast.ml;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Tests für {@link GermanHolidays}: bundesweite und landesspezifische Feiertage sowie das über das
 * Osterdatum berechnete bewegliche Datum.
 */
class GermanHolidaysTest {

    /**
     * Bundesweite Feiertage gelten unabhängig vom Bundesland (auch ohne Angabe).
     */
    @Test
    void bundesweiteFeiertage() {
        assertThat(GermanHolidays.isHoliday(LocalDate.of(2026, 1, 1), null)).isTrue();
        assertThat(GermanHolidays.isHoliday(LocalDate.of(2026, 10, 3), "Bayern")).isTrue();
        assertThat(GermanHolidays.isHoliday(LocalDate.of(2026, 12, 25), null)).isTrue();
    }

    /**
     * Ostermontag 2026 (6. April) wird über das Osterdatum korrekt erkannt.
     */
    @Test
    void ostermontagUeberOsterdatum() {
        assertThat(GermanHolidays.isHoliday(LocalDate.of(2026, 4, 6), null)).isTrue();
        assertThat(GermanHolidays.isHoliday(LocalDate.of(2026, 4, 3), null)).isTrue();
    }

    /**
     * Landesspezifische Feiertage gelten nur im jeweiligen Bundesland.
     */
    @Test
    void landesspezifischeFeiertage() {
        assertThat(GermanHolidays.isHoliday(LocalDate.of(2026, 1, 6), "BY")).isTrue();
        assertThat(GermanHolidays.isHoliday(LocalDate.of(2026, 1, 6), "Berlin")).isFalse();
        assertThat(GermanHolidays.isHoliday(LocalDate.of(2026, 10, 31), "Sachsen")).isTrue();
        assertThat(GermanHolidays.isHoliday(LocalDate.of(2026, 10, 31), "Bayern")).isFalse();
    }

    /**
     * Ein gewöhnlicher Werktag ist kein Feiertag.
     */
    @Test
    void gewoehnlicherWerktag() {
        assertThat(GermanHolidays.isHoliday(LocalDate.of(2026, 1, 2), null)).isFalse();
        assertThat(GermanHolidays.isHoliday(LocalDate.of(2026, 7, 15), "Sachsen")).isFalse();
    }

}
