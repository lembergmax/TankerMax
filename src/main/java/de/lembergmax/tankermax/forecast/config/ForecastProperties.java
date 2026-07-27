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

    /** Cron-Ausdruck des täglichen Vorhersagelaufs (Standard 0:00 Uhr). */
    @NotBlank
    private String cycleCron = "0 0 0 * * *";

    /** Zeitzone, in der der Cron des Vorhersagelaufs ausgewertet wird. */
    @NotBlank
    private String cycleZone = "Europe/Berlin";

    /**
     * Abstand zweier Neutrainings in Tagen.
     *
     * <p>Der Vorhersagelauf findet täglich statt, das Neutraining des Modells nur, wenn das
     * gespeicherte Modell älter als dieser Abstand ist. Bewusst nicht über den Cron-Ausdruck gelöst:
     * {@code &#42;/3} im Tagesfeld feuert an den Monatstagen 1, 4, … 28, 31 und liefert am Monatswechsel
     * einen Abstand von einem oder zwei statt drei Tagen.</p>
     */
    @Positive
    private int trainIntervalDays = 3;

    /** Verzeichnis, in dem die trainierten Modelle je Kraftstoffart abgelegt werden. */
    @NotBlank
    private String modelStorePath = "data/forecast-models";

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

    /**
     * Aufbewahrung der vollständigen Stundenkurve in Tagen.
     *
     * <p>Muss größer als {@code feedback-lookback-days} sein: Die Selbstkorrektur vergleicht
     * gespeicherte Vorhersagepunkte mit den tatsächlichen Preisen und verlöre bei gleichem Wert genau
     * die ältesten Stichproben ihres Messfensters.</p>
     */
    @Positive
    private int retentionCurveDays = 45;

    /** Aufbewahrung der kompakten Tageszusammenfassung samt Treffer-Abgleich in Tagen. */
    @Positive
    private int retentionSummaryDays = 365;

    /**
     * Schätzt zusätzlich ein unteres (q10) und oberes (q90) Quantil je Vorhersagepunkt.
     *
     * <p>Jedes Quantil ist ein vollständiges eigenes Modell und verdreifacht damit die Trainingszeit.
     * Auf dem Zielsystem (Raspberry Pi 5) ist das nicht tragbar, daher standardmäßig aus; das
     * Dashboard zeichnet das Band dann einfach nicht. Punktvorhersage und Tanktipp sind unberührt.</p>
     */
    private boolean quantilesEnabled = false;

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

    /**
     * Länge des Validierungsausschnitts in Tagen (jüngste Ausgangszeitpunkte).
     *
     * <p>Beispiele, deren Zielzeitpunkt in diesen Ausschnitt fällt, werden zusätzlich aus dem
     * Training entfernt. Ohne diese Sperrzone würde ein Trainingsbeispiel kurz vor der Grenze bis zu
     * {@code horizon-hours} in den Validierungszeitraum hineinreichen und den gemessenen Fehler
     * beschönigen.</p>
     */
    @Positive
    private int validationDays = 3;

    /**
     * Trainiert nach der Fehlermessung ein zweites Mal über den gesamten Zeitraum.
     *
     * <p>Der Validierungsausschnitt samt Sperrzone hält die jüngsten Tage aus dem Training heraus –
     * gerade die sind aber am aussagekräftigsten. Ist diese Einstellung aktiv, dient der erste Lauf
     * nur der Fehlermessung, und das ausgelieferte Modell wird anschließend auf allen Daten neu
     * gebildet. Der berichtete Fehler bleibt dabei die ehrliche Schätzung aus dem Validierungslauf.</p>
     */
    private boolean refitOnFullData = true;

    /** Startwert des Zufallsgenerators der Trainingsstichprobe (macht Läufe reproduzierbar). */
    private long samplingSeed = 20_260_726L;

    /**
     * Obergrenze der Validierungszeilen; darüber wird wie beim Training zufällig ausgedünnt.
     *
     * <p>Der Validierungsausschnitt wird zeilenweise gehalten, um den Fehler je Horizont-Abschnitt
     * ausweisen zu können. Er ist deutlich kleiner als die Trainingsmatrix und fällt beim
     * Speicherbedarf kaum ins Gewicht.</p>
     */
    @Positive
    private int maxValidationRows = 200_000;

    /**
     * Obergrenze der Trainingszeilen je Kraftstoffart; darüber wird zufällig ausgedünnt.
     *
     * <p>Grobe Abschätzung des Bedarfs: {@code Zeilen × (Merkmale + 1) × 8 Byte} für den Datensatz,
     * plus etwa {@code Zeilen × Merkmale × 4 Byte} für die von Smile intern gehaltenen Sortierindizes.
     * Bei 1,2 Mio Zeilen und 27 Merkmalen sind das rund 270 MB plus 130 MB, was mit dem für den
     * systemd-Dienst vorgesehenen {@code -Xmx1g} zusammenpasst. Bei knapperem Speicher entsprechend
     * senken.</p>
     */
    @Positive
    private long maxTrainRows = 1_200_000;

    /** Anzahl der Rechen-Threads des Modells (Smile). */
    @Min(1)
    private int modelThreads = 3;

    /**
     * Abstand der Ausgangszeitpunkte der Trainingsstichprobe in Stunden.
     *
     * <p>Standard 1: Jeder Rasterpunkt kommt als Ausgangszeitpunkt in Frage. Größere Werte lassen
     * Tagesstunden vollständig aus dem Training fallen – bei 6 kämen nur vier verschiedene
     * Tagesstunden vor –, während die Vorhersage später für jede Stunde abgefragt wird.</p>
     */
    @Positive
    private int trainOriginStepHours = 1;

    /**
     * Abstand der abgedeckten Horizonte der Trainingsstichprobe in Stunden.
     *
     * <p>Standard 1: Jeder ausgelieferte Horizont wird auch trainiert. Größere Werte lassen gerade
     * die kurzen Horizonte ungetraint, die für den Tanktipp am wichtigsten sind.</p>
     */
    @Positive
    private int trainHorizonStepHours = 1;

    /**
     * Anzahl der Bäume des Gradient-Boosting-Modells.
     *
     * <p>Die Rechenzeit steigt praktisch linear mit {@code Zeilen × Bäume}; auf dem Zielsystem wurden
     * rund 105 Minuten je Modell bei 1,5 Mio Zeilen und 600 Bäumen gemessen. Die Lernkapazität hängt
     * dagegen am Produkt aus Bäumen und {@link #gbdtShrinkage}, weshalb beide Werte gegenläufig
     * angepasst werden sollten.</p>
     */
    @Positive
    private int gbdtTrees = 350;

    /** Maximale Tiefe eines Baumes. */
    @Positive
    private int gbdtMaxDepth = 8;

    /** Maximale Zahl der Blattknoten eines Baumes. */
    @Positive
    private int gbdtMaxNodes = 64;

    /** Mindestanzahl der Beobachtungen je Blattknoten. */
    @Positive
    private int gbdtNodeSize = 40;

    /** Lernrate (Schrumpfung) des Boostings in (0, 1]; gegenläufig zu {@link #gbdtTrees} zu wählen. */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double gbdtShrinkage = 0.05;

    /** Stichprobenanteil je Baum (stochastisches Boosting). */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double gbdtSubsample = 0.7;

}
