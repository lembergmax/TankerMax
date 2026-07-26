package de.lembergmax.tankermax.forecast.ml;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Ein trainiertes Vorhersagemodell samt der Kennzahlen seines Trainingslaufs.
 *
 * <p>Training und Vorhersage laufen in unterschiedlichem Takt: Das Modell wird nur alle paar Tage neu
 * gebildet, die Vorhersagekurve dagegen täglich mit frischen Preisen. Damit auch an Tagen ohne
 * Training die Herkunft der Kurve nachvollziehbar bleibt, werden Trainingszeitpunkt, Zeilenzahl und
 * gemessener Fehler zusammen mit dem Modell abgelegt und in den Vorhersagelauf übernommen.</p>
 *
 * @param model        das trainierte Gradient-Boosting-Modell
 * @param trainedAt    Zeitpunkt des Trainings
 * @param modelVersion Kennung des Trainingslaufs
 * @param trainRows    Anzahl der genutzten Trainingszeilen
 * @param trainMae     mittlerer absoluter Validierungsfehler (Cent/Liter) oder {@code null}
 */
public record TrainedModel(GbdtForecastModel model, Instant trainedAt, String modelVersion,
                           int trainRows, Double trainMae) implements Serializable {

    /** Serialisierungskennung. */
    @Serial
    private static final long serialVersionUID = 1L;

}
