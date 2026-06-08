package de.lembergmax.tankermax.polling.domain;

/**
 * Status einer Tankstelle zum Zeitpunkt einer Preisbeobachtung.
 *
 * <p>Die drei Werte erlauben es, eine Lücke in der Preiskurve nach ihrer Ursache zu unterscheiden:
 * eine geschlossene Tankstelle ({@link #CLOSED}), eine geöffnete Tankstelle ohne von der API
 * gelieferten Preis ({@link #UNAVAILABLE}) und – durch das Fehlen einer Beobachtung – einen
 * Ausfall der Erfassung selbst.</p>
 */
public enum ObservationStatus {

    /** Die Tankstelle ist geöffnet und hat mindestens einen gültigen Preis geliefert. */
    OPEN,

    /** Die Tankstelle ist geschlossen. */
    CLOSED,

    /** Die Tankstelle ist geöffnet, lieferte aber keinen verwertbaren Preis. */
    UNAVAILABLE;

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
