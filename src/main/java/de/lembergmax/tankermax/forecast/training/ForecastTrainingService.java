package de.lembergmax.tankermax.forecast.training;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import de.lembergmax.tankermax.forecast.ml.ForecastContext;
import de.lembergmax.tankermax.forecast.ml.StationObservations;
import de.lembergmax.tankermax.forecast.ml.TrainedModel;
import de.lembergmax.tankermax.forecast.repository.ForecastRunRepository;
import de.lembergmax.tankermax.forecast.training.ForecastModelTrainer.Result;
import de.lembergmax.tankermax.polling.domain.FuelType;
import de.lembergmax.tankermax.polling.repository.FuelTypeRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Steuert den täglichen Vorhersagelauf und das seltenere Neutraining des Modells.
 *
 * <p>Training und Vorhersage laufen bewusst in unterschiedlichem Takt. Das Modell wird nur alle
 * {@code train-interval-days} Tage neu gebildet – dafür über den vollen Datenbestand und mit
 * entsprechend großzügigen Hyperparametern. Die Vorhersagekurve dagegen entsteht bei jedem Lauf neu,
 * also täglich, weil sie auf dem aktuellen Preis als Ausgangswert aufsetzt: Bei einem Horizont von
 * 72 Stunden wäre eine drei Tage alte Kurve am Ende ihres Zeitraums nicht nur leer, sondern auch von
 * einem drei Tage alten Ausgangspreis abgeleitet.</p>
 *
 * <p>Ob trainiert wird, entscheidet das Alter des gespeicherten Modells, nicht der Cron-Ausdruck:
 * {@code &#42;/3} im Tagesfeld eines Cron feuert an den Monatstagen 1, 4, … 28, 31 und ergäbe am
 * Monatswechsel einen Abstand von einem oder zwei Tagen. Zusätzlich plant der {@link ApplicationRunner}
 * kurz nach dem Start einen Nachhol-Lauf, falls für heute noch keine Vorhersage vorliegt. Ein
 * {@link ReentrantLock} stellt sicher, dass nie zwei Läufe gleichzeitig arbeiten. Nur im Profil
 * {@code ingest} aktiv.</p>
 */
@Service
@Profile("ingest")
@RequiredArgsConstructor
public class ForecastTrainingService implements ApplicationRunner {

    /** Logger dieser Klasse. */
    private static final Logger LOG = LoggerFactory.getLogger(ForecastTrainingService.class);

    /** Zeitzone, in der „heute" für den Nachhol-Lauf bestimmt wird. */
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Kraftstoffarten, für die nacheinander gerechnet wird. */
    private static final List<String> FUEL_CODES = List.of("E5", "E10", "DIESEL");

    /** Verzögerung des Nachhol-Laufs nach dem Start in Sekunden (lässt die Startphase abklingen). */
    private static final long STARTUP_DELAY_SECONDS = 120;

    /** Sekunden je Minute, zur Umrechnung der Auflösung. */
    private static final long SECONDS_PER_MINUTE = 60;

    /** Sekunden je Tag. */
    private static final long SECONDS_PER_DAY = 86_400L;

    /** Sperre gegen gleichzeitige Läufe. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Konfiguration der Vorhersage. */
    private final ForecastProperties props;

    /** Lädt die historischen Beobachtungen. */
    private final ForecastDataLoader dataLoader;

    /** Trainiert das Modell und erzeugt die Vorhersage. */
    private final ForecastModelTrainer trainer;

    /** Legt trainierte Modelle ab und lädt sie wieder. */
    private final ForecastModelStore modelStore;

    /** Misst und schreibt die Selbstkorrektur fort. */
    private final ForecastFeedbackService feedbackService;

    /** Gleicht vergangene Vorhersagen ab. */
    private final ForecastAccuracyService accuracyService;

    /** Speichert die Ergebnisse und räumt alte Daten auf. */
    private final ForecastPersistenceService persistenceService;

    /** Repository der Kraftstoffarten. */
    private final FuelTypeRepository fuelTypeRepository;

    /** Repository der Vorhersageläufe (für die Nachhol-Prüfung). */
    private final ForecastRunRepository runRepository;

    /** Aufgabenplaner für den verzögerten Nachhol-Lauf. */
    private final TaskScheduler taskScheduler;

    /**
     * Begrenzt die Rechen-Threads des Modells (Smile) auf den konfigurierten Wert, damit das Training
     * den Raspberry Pi nicht vollständig auslastet.
     */
    @PostConstruct
    public void configureModelThreads() {
        System.setProperty("smile.threads", String.valueOf(props.getModelThreads()));
    }

    /**
     * Plant nach dem Start einen einmaligen Nachhol-Lauf, falls aktiviert.
     *
     * @param args Argumente des Anwendungsstarts
     */
    @Override
    public void run(final ApplicationArguments args) {
        if (!props.isEnabled() || !props.isCatchUpOnStartup()) {
            return;
        }
        taskScheduler.schedule(this::catchUp, Instant.now().plusSeconds(STARTUP_DELAY_SECONDS));
    }

    /**
     * Führt den täglichen Vorhersagelauf zum konfigurierten Zeitpunkt aus.
     */
    @Scheduled(cron = "${tankermax.forecast.cycle-cron}", zone = "${tankermax.forecast.cycle-zone}")
    public void scheduledCycle() {
        if (!props.isEnabled()) {
            return;
        }
        runCycle();
    }

    /**
     * Holt die Vorhersage nach dem Start nach, sofern für heute noch keine vorliegt.
     */
    private void catchUp() {
        if (hasRunToday()) {
            return;
        }
        LOG.info("Für heute liegt noch keine Vorhersage vor – Nachhol-Lauf wird gestartet.");
        runCycle();
    }

    /**
     * Prüft, ob heute bereits eine Vorhersage erzeugt wurde.
     *
     * @return {@code true}, wenn heute bereits ein Lauf stattfand
     */
    private boolean hasRunToday() {
        final Instant startOfToday = LocalDate.now(BERLIN).atStartOfDay(BERLIN).toInstant();
        return runRepository.countByGeneratedAtGreaterThanEqual(startOfToday) > 0;
    }

    /**
     * Führt einen vollständigen Lauf über alle Kraftstoffarten aus, sofern nicht bereits einer läuft.
     */
    private void runCycle() {
        if (!lock.tryLock()) {
            LOG.info("Vorhersagelauf läuft bereits – dieser Lauf wird übersprungen.");
            return;
        }
        try {
            final Instant now = Instant.now();
            final long stepSeconds = (long) props.getResolutionMinutes() * SECONDS_PER_MINUTE;
            final LocalDateTime windowStart = LocalDateTime.now(ZoneOffset.UTC)
                    .minusDays(props.getTrainWindowDays());
            LOG.info("===== KI-Vorhersage: Lauf START =====");
            for (final String code : FUEL_CODES) {
                try {
                    processFuel(code, windowStart, stepSeconds, now);
                } catch (final RuntimeException ex) {
                    LOG.warn("Vorhersage für {} fehlgeschlagen, Lauf wird mit den übrigen Kraftstoffen "
                            + "fortgesetzt: {}", code, ex.getMessage(), ex);
                }
            }
            accuracyService.evaluatePending(props);
            persistenceService.prune(now, props);
            LOG.info("===== KI-Vorhersage: Lauf ENDE =====");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Führt Laden, gegebenenfalls Training, Inferenz, Speichern und Selbstkorrektur für eine
     * Kraftstoffart aus.
     *
     * @param code        Datenbank-Code des Kraftstoffs
     * @param windowStart Beginn des Datenfensters (UTC)
     * @param stepSeconds Rasterabstand in Sekunden
     * @param now         Zeitpunkt des Laufs
     */
    private void processFuel(final String code, final LocalDateTime windowStart, final long stepSeconds,
                             final Instant now) {
        final FuelType fuelType = fuelTypeRepository.findByCode(code).orElse(null);
        if (fuelType == null) {
            LOG.warn("Kraftstoffart {} ist nicht angelegt – übersprungen.", code);
            return;
        }
        final List<StationObservations> observations = dataLoader.load(code, windowStart);
        if (observations.isEmpty()) {
            LOG.info("Keine Historie für {} – Vorhersage übersprungen.", code);
            return;
        }
        final ForecastContext ctx = ForecastContext.build(observations, now.getEpochSecond(), stepSeconds);
        final TrainedModel trained = obtainModel(code, ctx, now);
        if (trained == null) {
            LOG.info("Zu wenig Historie für ein Modell ({}) – Vorhersage übersprungen.", code);
            return;
        }

        final Map<String, double[]> bias = feedbackService.loadBias(fuelType.getId());
        final Instant startedAt = Instant.now();
        final Result result = trainer.infer(code, ctx, trained, bias, now);
        persistenceService.persist(fuelType.getId(), result);
        feedbackService.recompute(fuelType, ctx, now, props);
        LOG.info("Vorhersage {}: {} Tankstellen in {} s, Modell vom {} ({} Trainingszeilen, "
                        + "Validierungs-MAE {}).",
                code, result.stations().size(), Duration.between(startedAt, Instant.now()).toSeconds(),
                trained.trainedAt(), trained.trainRows(),
                trained.trainMae() == null ? "n/v" : String.format("%.3f ct", trained.trainMae()));
    }

    /**
     * Liefert das zu verwendende Modell: das gespeicherte, solange es jung genug ist, sonst ein frisch
     * trainiertes.
     *
     * @param code Datenbank-Code des Kraftstoffs
     * @param ctx  vorberechneter Kontext aller Tankstellen
     * @param now  Zeitpunkt des Laufs
     * @return Modell oder {@code null}, wenn kein Modell gebildet werden konnte
     */
    private TrainedModel obtainModel(final String code, final ForecastContext ctx, final Instant now) {
        final Optional<TrainedModel> stored = modelStore.load(code);
        if (stored.isPresent() && !isStale(stored.get(), now)) {
            return stored.get();
        }
        LOG.info("Vorhersage {}: Neutraining beginnt ({}).", code,
                stored.isEmpty() ? "kein verwendbares Modell vorhanden"
                        : "gespeichertes Modell ist älter als " + props.getTrainIntervalDays() + " Tage");
        final Instant startedAt = Instant.now();
        final TrainedModel trained = trainer.train(code, ctx, now);
        if (trained == null) {
            return stored.orElse(null);
        }
        LOG.info("Vorhersage {}: Neutraining abgeschlossen in {} min.", code,
                Duration.between(startedAt, Instant.now()).toMinutes());
        modelStore.save(code, trained);
        return trained;
    }

    /**
     * Prüft, ob ein gespeichertes Modell neu trainiert werden muss.
     *
     * @param trained gespeichertes Modell
     * @param now     Zeitpunkt des Laufs
     * @return {@code true}, wenn das Modell älter als der eingestellte Abstand ist
     */
    private boolean isStale(final TrainedModel trained, final Instant now) {
        return trained.trainedAt()
                .isBefore(now.minusSeconds((long) props.getTrainIntervalDays() * SECONDS_PER_DAY));
    }

}
