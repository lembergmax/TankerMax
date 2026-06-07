package de.lembergmax.tankermax.domain;

/**
 * Status einer Tankstelle zum Zeitpunkt einer Preisbeobachtung.
 */
public enum ObservationStatus {

    /** Die Tankstelle ist geöffnet. */
    OPEN,

    /** Die Tankstelle ist geschlossen. */
    CLOSED;

    /**
     * Leitet den Status aus dem Geöffnet-Kennzeichen der Listen-Schnittstelle ab.
     *
     * @param open {@code true}, wenn die Tankstelle geöffnet ist
     * @return {@link #OPEN} bei geöffneter, sonst {@link #CLOSED}
     */
    public static ObservationStatus fromOpenFlag(final boolean open) {
        return open ? OPEN : CLOSED;
    }

}
