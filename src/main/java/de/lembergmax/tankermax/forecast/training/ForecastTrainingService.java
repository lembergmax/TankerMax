package de.lembergmax.tankermax.forecast.training;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import de.lembergmax.tankermax.forecast.ml.ForecastContext;
import de.lembergmax.tankermax.forecast.ml.StationObservations;
import de.lembergmax.tankermax.forecast.training.ForecastModelTrainer.Result;
import de.lembergmax.tankermax.polling.domain.FuelType;
import de.lembergmax.tankermax.polling.repository.FuelTypeRepository;
import de.lembergmax.tankermax.forecast.repository.ForecastRunRepository;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Steuert das tägliche Neutraining der Preisvorhersage und das einmalige Nachholen nach einem Neustart.
 *
 * <p>Der {@link Scheduled}-Cron stößt das Training täglich zum konfigurierten Zeitpunkt (Standard
 * 0:00 Uhr) an. Zusätzlich plant der {@link ApplicationRunner} kurz nach dem Start einen Nachhol-Lauf,
 * falls für den heutigen Tag noch keine Vorhersage vorliegt (etwa nach einem Neustart tagsüber). Ein
 * {@link ReentrantLock} stellt sicher, dass nie zwei Läufe gleichzeitig arbeiten. Je Kraftstoffart
 * werden die Historie geladen, das Modell trainiert, die Kurve und die Tageszusammenfassungen
 * gespeichert sowie die Selbstkorrektur fortgeschrieben; danach werden vergangene Vorhersagen
 * abgeglichen und alte Daten aufgeräumt. Nur im Profil {@code ingest} aktiv.</p>
 */
@Service
@Profile("ingest")
@RequiredArgsConstructor
public class ForecastTrainingService implements ApplicationRunner {

    /** Logger dieser Klasse. */
    private static final Logger LOG = LoggerFactory.getLogger(ForecastTrainingService.class);

    /** Zeitzone, in der „heute" für den Nachhol-Lauf bestimmt wird. */
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Kraftstoffarten, für die nacheinander trainiert wird. */
    private static final List<String> FUEL_CODES = List.of("E5", "E10", "DIESEL");

    /** Verzögerung des Nachhol-Laufs nach dem Start in Sekunden (lässt die Startphase abklingen). */
    private static final long STARTUP_DELAY_SECONDS = 120;

    /** Sekunden je Minute, zur Umrechnung der Auflösung. */
    private static final long SECONDS_PER_MINUTE = 60;

    /** Sperre gegen gleichzeitige Läufe. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Konfiguration der Vorhersage. */
    private final ForecastProperties props;

    /** Lädt die historischen Beobachtungen. */
    private final ForecastDataLoader dataLoader;

    /** Trainiert das Modell und erzeugt die Vorhersage. */
    private final ForecastModelTrainer trainer;

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
     * den Raspberry Pi nicht auslastet.
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
     * Führt das tägliche Neutraining zum konfigurierten Zeitpunkt aus.
     */
    @Scheduled(cron = "${tankermax.forecast.train-cron}", zone = "${tankermax.forecast.train-zone}")
    public void scheduledTraining() {
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
     * Prüft, ob heute bereits ein Vorhersagelauf erzeugt wurde.
     *
     * @return {@code true}, wenn heute bereits trainiert wurde
     */
    private boolean hasRunToday() {
        final Instant startOfToday = LocalDate.now(BERLIN).atStartOfDay(BERLIN).toInstant();
        return runRepository.countByGeneratedAtGreaterThanEqual(startOfToday) > 0;
    }

    /**
     * Führt einen vollständigen Vorhersagelauf über alle Kraftstoffarten aus, sofern nicht bereits
     * einer läuft.
     */
    private void runCycle() {
        if (!lock.tryLock()) {
            LOG.info("Vorhersage-Training läuft bereits – dieser Lauf wird übersprungen.");
            return;
        }
        try {
            final Instant now = Instant.now();
            final long stepSeconds = (long) props.getResolutionMinutes() * SECONDS_PER_MINUTE;
            final LocalDateTime windowStart = LocalDateTime.now(ZoneOffset.UTC).minusDays(props.getTrainWindowDays());
            LOG.info("===== KI-Vorhersage: Training START =====");
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
            LOG.info("===== KI-Vorhersage: Training ENDE =====");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Führt Laden, Training, Inferenz, Speichern und Selbstkorrektur für eine Kraftstoffart aus.
     *
     * @param code        Datenbank-Code des Kraftstoffs
     * @param windowStart Beginn des Trainingsfensters (UTC)
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
        final Map<String, double[]> bias = feedbackService.loadBias(fuelType.getId());
        final Result result = trainer.trainAndInfer(code, ctx, bias, now);
        if (result == null) {
            LOG.info("Zu wenig Historie für ein Modell ({}) – Vorhersage übersprungen.", code);
            return;
        }
        persistenceService.persist(fuelType.getId(), result);
        feedbackService.recompute(fuelType, ctx, now, props);
        LOG.info("Vorhersage {}: {} Tankstellen, {} Trainingszeilen, Validierungs-MAE {}",
                code, result.stations().size(), result.trainRows(),
                result.trainMae() == null ? "n/v" : String.format("%.3f ct", result.trainMae()));
    }

}
