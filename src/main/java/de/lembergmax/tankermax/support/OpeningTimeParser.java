package de.lembergmax.tankermax.support;

import java.time.DateTimeException;
import java.time.LocalTime;

/**
 * Wandelt die von der Tankerkönig-API gelieferten Uhrzeit-Zeichenketten in
 * {@link LocalTime} um.
 */
public final class OpeningTimeParser {

    /** Tagesende, auf das der API-Wert {@code 24:00:00} abgebildet wird. */
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    /** Von der API verwendeter Sonderwert für das Tagesende. */
    private static final String MIDNIGHT_END_TOKEN = "24:00:00";

    /**
     * Verhindert die Instanziierung dieser reinen Hilfsklasse.
     */
    private OpeningTimeParser() {
    }

    /**
     * Wandelt eine Uhrzeit-Zeichenkette der API in eine {@link LocalTime} um.
     *
     * <p>Der Sonderwert {@code 24:00:00} wird auf das Tagesende abgebildet. Nicht
     * interpretierbare Werte ergeben {@code null}.</p>
     *
     * @param value Uhrzeit im Format {@code HH:mm:ss} oder {@code null}
     * @return die geparste Uhrzeit oder {@code null}, falls der Wert leer oder ungültig ist
     */
    public static LocalTime parse(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final String trimmed = value.trim();
        if (MIDNIGHT_END_TOKEN.equals(trimmed)) {
            return END_OF_DAY;
        }
        try {
            return LocalTime.parse(trimmed);
        } catch (final DateTimeException ex) {
            return null;
        }
    }

}
