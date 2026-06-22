package de.lembergmax.tankermax.forecast.domain;

/**
 * Treffer-Klasse einer rückblickend ausgewerteten Vorhersage.
 *
 * <p>Sobald der Vorhersagetag verstrichen ist, wird die prognostizierte mit der tatsächlich
 * eingetretenen Preislage verglichen und die Abweichung in eine der drei Klassen eingeordnet. Die
 * Schwellen sind über {@code tankermax.forecast.accuracy-*-max-ct} konfigurierbar.</p>
 */
public enum AccuracyClass {

    /** Die Vorhersage lag innerhalb der engen Schwelle – richtig. */
    RICHTIG,

    /** Die Vorhersage lag innerhalb der weiteren Schwelle – fast richtig. */
    FAST,

    /** Die Vorhersage lag außerhalb beider Schwellen – falsch. */
    FALSCH

}
