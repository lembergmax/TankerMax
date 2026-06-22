package de.lembergmax.tankermax.forecast.domain;

/**
 * Tanktipp einer Tageszusammenfassung: ob es sich lohnt, jetzt zu tanken oder zu warten.
 *
 * <p>Die Empfehlung leitet sich aus der prognostizierten Preiskurve ab: Wird innerhalb des
 * konfigurierten Wartefensters ein hinreichend günstigeres Tief erwartet, wird zum Warten geraten;
 * andernfalls zum Tanken. Liegt keine belastbare Aussage vor, bleibt die Empfehlung neutral.</p>
 */
public enum RefuelRecommendation {

    /** Jetzt tanken – ein nennenswert günstigeres Tief ist im Wartefenster nicht zu erwarten. */
    TANKEN,

    /** Warten – innerhalb des Wartefensters wird ein hinreichend günstigeres Tief erwartet. */
    WARTEN,

    /** Keine belastbare Empfehlung möglich. */
    NEUTRAL

}
