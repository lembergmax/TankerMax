package de.lembergmax.tankermax.support;

/**
 * Hilfsfunktionen zur Normalisierung deutscher Postleitzahlen.
 */
public final class PostalCode {

    /** Vorgeschriebene Länge einer deutschen Postleitzahl. */
    private static final int LENGTH = 5;

    /**
     * Verhindert die Instanziierung dieser reinen Hilfsklasse.
     */
    private PostalCode() {
    }

    /**
     * Normalisiert eine Postleitzahl auf fünf Stellen.
     *
     * <p>Da die API Postleitzahlen als Zahl liefern kann, gehen führende Nullen
     * verloren. Diese werden hier wiederhergestellt.</p>
     *
     * @param raw Postleitzahl, wie von der API geliefert
     * @return fünfstellige Postleitzahl oder {@code null}, falls keine Angabe vorliegt
     */
    public static String normalize(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        final String trimmed = raw.trim();
        if (trimmed.length() >= LENGTH) {
            return trimmed;
        }
        return "0".repeat(LENGTH - trimmed.length()) + trimmed;
    }

}
