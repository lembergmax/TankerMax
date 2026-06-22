package de.lembergmax.tankermax.forecast.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Gebündelte Benutzer-Einstellungen der KI-Preisvorhersage.
 *
 * <p>Sämtliche Werte stammen aus dem Abschnitt {@code tankermax.forecast.*} der
 * {@code application.properties} und werden beim Anwendungsstart über Bean-Validation geprüft. Die
 * Eigenschaften steuern den Trainingszeitpunkt, den Vorhersagehorizont, die Aufbewahrungsdauer, die
 * Treffer-Schwellen, die Selbstkorrektur aus früheren Vorhersagen, den Tanktipp sowie die bewusst
 * klein gehaltenen Modell-Hyperparameter, damit Training und Inferenz auch auf einem Raspberry Pi
 * mit geringem Arbeitsspeicher- und CPU-Verbrauch laufen.</p>
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "tankermax.forecast")
public class ForecastProperties {

    /** Schaltet die gesamte Vorhersage an oder aus (Training und Auslieferung). */
    private boolean enabled = true;

    /** Cron-Ausdruck des täglichen Neutrainings (Standard 0:00 Uhr). */
    @NotBlank
    private String trainCron = "0 0 0 * * *";

    /** Zeitzone, in der der Trainings-Cron ausgewertet wird. */
    @NotBlank
    private String trainZone = "Europe/Berlin";

    /** Holt die Vorhersage nach einem Neustart einmalig nach, falls für heute noch keine vorliegt. */
    private boolean catchUpOnStartup = true;

    /** Vorhersagehorizont in Stunden ab dem aktuellen Zeitpunkt (höchstens drei Tage). */
    @Min(1)
    @Max(72)
    private int horizonHours = 72;

    /** Zeitliche Auflösung der Vorhersagekurve in Minuten (60 = stündlich). */
    @Min(15)
    private int resolutionMinutes = 60;

    /** Länge des rollierenden Trainingsfensters in Tagen. */
    @Positive
    private int trainWindowDays = 90;

    /** Mindestanzahl an Tagen mit Historie, ab der eine Tankstelle eine Vorhersage erhält. */
    @Positive
    private int minHistoryDays = 14;

    /** Aufbewahrung der vollständigen Stundenkurve in Tagen. */
    @Positive
    private int retentionCurveDays = 30;

    /** Aufbewahrung der kompakten Tageszusammenfassung samt Treffer-Abgleich in Tagen. */
    @Positive
    private int retentionSummaryDays = 365;

    /** Schätzt zusätzlich ein unteres (q10) und oberes (q90) Quantil je Vorhersagepunkt. */
    private boolean quantilesEnabled = true;

    /** Unteres Quantil des Unsicherheitsbandes (zwischen 0 und 1). */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double quantileLow = 0.10;

    /** Oberes Quantil des Unsicherheitsbandes (zwischen 0 und 1). */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double quantileHigh = 0.90;

    /** Abweichung (Cent/Liter) bis zu der eine Vorhersage als „richtig" gilt. */
    @Positive
    private double accuracyCorrectMaxCt = 1.0;

    /** Abweichung (Cent/Liter) bis zu der eine Vorhersage als „fast richtig" gilt. */
    @Positive
    private double accuracyAlmostMaxCt = 3.0;

    /** Zeitfenster (Tage), über das die systematische Abweichung früherer Vorhersagen gemessen wird. */
    @Positive
    private int feedbackLookbackDays = 30;

    /** Deckel (Cent/Liter) der je Tankstelle und Horizont gegengerechneten systematischen Abweichung. */
    @Positive
    private double feedbackMaxCorrectionCt = 5.0;

    /** Tankvolumen (Liter), mit dem die Ersparnis des Tanktipps gerechnet wird. */
    @Positive
    private int tankVolumeLiters = 50;

    /** Mindestersparnis (Cent/Liter), ab der zum Warten geraten wird. */
    @Positive
    private double recommendationThresholdCt = 1.5;

    /** Zeitfenster (Minuten), innerhalb dessen ein günstigeres Tief erreichbar sein muss. */
    @Positive
    private int recommendationMaxWaitMinutes = 480;

    /** Obergrenze der Trainingszeilen je Kraftstoffart; darüber wird gleichmäßig ausgedünnt. */
    @Positive
    private long maxTrainRows = 1_200_000;

    /** Anzahl der Rechen-Threads des Modells (Smile). */
    @Min(1)
    private int modelThreads = 2;

    /** Abstand der Ausgangszeitpunkte der Trainingsstichprobe in Stunden. */
    @Positive
    private int trainOriginStepHours = 6;

    /** Abstand der abgedeckten Horizonte der Trainingsstichprobe in Stunden. */
    @Positive
    private int trainHorizonStepHours = 3;

    /** Anzahl der Bäume des Gradient-Boosting-Modells. */
    @Positive
    private int gbdtTrees = 300;

    /** Maximale Tiefe eines Baumes. */
    @Positive
    private int gbdtMaxDepth = 6;

    /** Maximale Zahl der Blattknoten eines Baumes. */
    @Positive
    private int gbdtMaxNodes = 32;

    /** Mindestanzahl der Beobachtungen je Blattknoten. */
    @Positive
    private int gbdtNodeSize = 20;

    /** Lernrate (Schrumpfung) des Boostings in (0, 1]. */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double gbdtShrinkage = 0.05;

    /** Stichprobenanteil je Baum (stochastisches Boosting). */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double gbdtSubsample = 0.7;

}
