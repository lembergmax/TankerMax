package de.lembergmax.tankermax.forecast.training;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import de.lembergmax.tankermax.forecast.domain.RefuelRecommendation;
import de.lembergmax.tankermax.forecast.ml.ForecastContext;
import de.lembergmax.tankermax.forecast.ml.ForecastFeatures;
import de.lembergmax.tankermax.forecast.ml.GbdtForecastModel;
import de.lembergmax.tankermax.forecast.ml.HourlyGrid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Trainiert das Vorhersagemodell einer Kraftstoffart und erzeugt daraus die Vorhersagekurve samt
 * Tageszusammenfassungen.
 *
 * <p>Aus dem Zeitraster aller Tankstellen werden Trainingsbeispiele der Form (Merkmale → Preis am
 * Zielzeitpunkt) gebildet, wobei der Horizont selbst ein Merkmal ist (ein gemeinsames Modell je
 * Kraftstoffart liefert dennoch eine individuelle Kurve je Tankstelle). Die jüngsten Tage dienen als
 * zeitlicher Validierungsausschnitt zur Messung des mittleren absoluten Fehlers; die Anzahl der
 * Trainingszeilen wird auf {@code tankermax.forecast.max-train-rows} gedeckelt, um den Arbeitsspeicher
 * des Raspberry Pi zu schonen. Anschließend wird je Tankstelle die Kurve über den vollen Horizont
 * vorhergesagt, um die gemessene Selbstkorrektur gegengerechnet und zu Tageskennzahlen samt Tanktipp
 * verdichtet. Reine Rechenarbeit ohne Datenbankzugriff; nur im Profil {@code ingest} aktiv.</p>
 */
@Service
@Profile("ingest")
@RequiredArgsConstructor
public class ForecastModelTrainer {

    /** Zeitzone, in der Vorhersagetage und Tanktipp bestimmt werden. */
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Plausibler kleinster Literpreis (Euro); Vorhersagen werden darauf begrenzt. */
    private static final double MIN_PRICE = 0.5;

    /** Plausibler größter Literpreis (Euro); Vorhersagen werden darauf begrenzt. */
    private static final double MAX_PRICE = 3.0;

    /** Sekunden je Tag. */
    private static final long SECONDS_PER_DAY = 86_400L;

    /** Länge des Validierungsausschnitts in Tagen (jüngste Ausgangszeitpunkte). */
    private static final int VALIDATION_DAYS = 7;

    /** Obergrenze der Validierungszeilen. */
    private static final int VALIDATION_CAP = 200_000;

    /** Konfiguration der Vorhersage. */
    private final ForecastProperties props;

    /**
     * Trainiert das Modell und erzeugt die Vorhersage einer Kraftstoffart.
     *
     * @param fuelCode      Datenbank-Code des Kraftstoffs
     * @param ctx           vorberechneter Kontext aller Tankstellen
     * @param biasByStation Selbstkorrektur je Tankstelle (Cent je Horizont-Abschnitt) oder leer
     * @param now           Erzeugungszeitpunkt dieses Laufs
     * @return Vorhersage-Ergebnis oder {@code null}, wenn zu wenige Trainingsdaten vorliegen
     */
    public Result trainAndInfer(final String fuelCode, final ForecastContext ctx,
                                final Map<String, double[]> biasByStation, final Instant now) {
        final int sph = ctx.stepsPerHour();
        final long stepSeconds = ctx.stepSeconds();
        final int horizonSteps = props.getHorizonHours() * sph;
        final int originStep = Math.max(1, props.getTrainOriginStepHours() * sph);
        final int horizonStep = Math.max(1, props.getTrainHorizonStepHours() * sph);
        final int minHistorySteps = props.getMinHistoryDays() * 24 * sph;
        final int lagWarmup = 24 * sph;
        final long validationCutoff = now.getEpochSecond() - (long) VALIDATION_DAYS * SECONDS_PER_DAY;

        final long[] counts = countSamples(ctx, minHistorySteps, lagWarmup, originStep,
                horizonStep, horizonSteps, validationCutoff);
        final long trainTotal = counts[0];
        final long validTotal = counts[1];
        if (trainTotal == 0) {
            return null;
        }
        final int trainStride = (int) Math.max(1, (trainTotal + props.getMaxTrainRows() - 1) / props.getMaxTrainRows());
        final int validStride = (int) Math.max(1, (validTotal + VALIDATION_CAP - 1) / VALIDATION_CAP);

        final Samples samples = collectSamples(ctx, minHistorySteps, lagWarmup, originStep, horizonStep,
                horizonSteps, validationCutoff, trainStride, validStride,
                (int) Math.min(props.getMaxTrainRows(), trainTotal),
                (int) Math.min(VALIDATION_CAP, validTotal));
        if (samples.trainCount == 0) {
            return null;
        }

        final double[][] trainX = Arrays.copyOf(samples.trainX, samples.trainCount);
        final double[] trainY = Arrays.copyOf(samples.trainY, samples.trainCount);
        final GbdtForecastModel model = GbdtForecastModel.train(trainX, trainY, props);
        final Double mae = validationMae(model, samples);

        final List<StationForecast> stations = infer(ctx, model, biasByStation, horizonSteps, stepSeconds, minHistorySteps);
        final long originEpoch = floorToStep(now.getEpochSecond(), stepSeconds);
        final String modelVersion = String.valueOf(now.getEpochSecond());
        return new Result(fuelCode, now, Instant.ofEpochSecond(originEpoch), props.getHorizonHours(),
                props.getResolutionMinutes(), modelVersion, samples.trainCount, mae, stations);
    }

    /**
     * Zählt die möglichen Trainings- und Validierungsbeispiele, um die Stichproben-Schrittweite und
     * die Feldgrößen vorab festzulegen.
     *
     * @param ctx              Kontext
     * @param minHistorySteps  Mindesthistorie in Rasterschritten
     * @param lagWarmup        Vorlauf für die Verzögerungs-Features
     * @param originStep       Abstand der Ausgangszeitpunkte
     * @param horizonStep      Abstand der Horizonte
     * @param horizonSteps     größter Horizont in Rasterschritten
     * @param validationCutoff Grenze, ab der ein Ausgangszeitpunkt zur Validierung zählt
     * @return Array {Anzahl Trainingsbeispiele, Anzahl Validierungsbeispiele}
     */
    private long[] countSamples(final ForecastContext ctx, final int minHistorySteps, final int lagWarmup,
                                final int originStep, final int horizonStep, final int horizonSteps,
                                final long validationCutoff) {
        long train = 0;
        long valid = 0;
        for (final String stationId : ctx.stationIds()) {
            final HourlyGrid grid = ctx.grid(stationId);
            if (grid.size() < minHistorySteps) {
                continue;
            }
            final int last = grid.lastIndex();
            for (int origin = lagWarmup; origin <= last - 1; origin += originStep) {
                final boolean isValidation = grid.timeAt(origin) >= validationCutoff;
                for (int h = horizonStep; h <= horizonSteps; h += horizonStep) {
                    if (origin + h > last) {
                        break;
                    }
                    if (isValidation) {
                        valid++;
                    } else {
                        train++;
                    }
                }
            }
        }
        return new long[]{train, valid};
    }

    /**
     * Baut die Trainings- und Validierungsmatrizen anhand der vorab bestimmten Schrittweiten.
     *
     * @param ctx              Kontext
     * @param minHistorySteps  Mindesthistorie in Rasterschritten
     * @param lagWarmup        Vorlauf für die Verzögerungs-Features
     * @param originStep       Abstand der Ausgangszeitpunkte
     * @param horizonStep      Abstand der Horizonte
     * @param horizonSteps     größter Horizont in Rasterschritten
     * @param validationCutoff Grenze, ab der ein Ausgangszeitpunkt zur Validierung zählt
     * @param trainStride      nur jedes n-te Trainingsbeispiel wird übernommen
     * @param validStride      nur jedes n-te Validierungsbeispiel wird übernommen
     * @param trainCapacity    Feldgröße für die Trainingsbeispiele
     * @param validCapacity    Feldgröße für die Validierungsbeispiele
     * @return gefüllte Stichproben
     */
    private Samples collectSamples(final ForecastContext ctx, final int minHistorySteps, final int lagWarmup,
                                   final int originStep, final int horizonStep, final int horizonSteps,
                                   final long validationCutoff, final int trainStride, final int validStride,
                                   final int trainCapacity, final int validCapacity) {
        final Samples samples = new Samples(trainCapacity, validCapacity);
        long trainSeen = 0;
        long validSeen = 0;
        for (final String stationId : ctx.stationIds()) {
            final HourlyGrid grid = ctx.grid(stationId);
            if (grid.size() < minHistorySteps) {
                continue;
            }
            final int last = grid.lastIndex();
            for (int origin = lagWarmup; origin <= last - 1; origin += originStep) {
                final boolean isValidation = grid.timeAt(origin) >= validationCutoff;
                for (int h = horizonStep; h <= horizonSteps; h += horizonStep) {
                    if (origin + h > last) {
                        break;
                    }
                    final double target = grid.priceAt(origin + h);
                    if (isValidation) {
                        if (validSeen % validStride == 0 && samples.validCount < validCapacity) {
                            samples.validX[samples.validCount] = ForecastFeatures.build(ctx, stationId, origin, h);
                            samples.validY[samples.validCount] = target;
                            samples.validCount++;
                        }
                        validSeen++;
                    } else {
                        if (trainSeen % trainStride == 0 && samples.trainCount < trainCapacity) {
                            samples.trainX[samples.trainCount] = ForecastFeatures.build(ctx, stationId, origin, h);
                            samples.trainY[samples.trainCount] = target;
                            samples.trainCount++;
                        }
                        trainSeen++;
                    }
                }
            }
        }
        return samples;
    }

    /**
     * Misst den mittleren absoluten Fehler (Cent/Liter) auf dem Validierungsausschnitt.
     *
     * @param model   trainiertes Modell
     * @param samples Stichproben mit Validierungsteil
     * @return mittlerer absoluter Fehler in Cent/Liter oder {@code null}, wenn kein Validierungsteil vorliegt
     */
    private Double validationMae(final GbdtForecastModel model, final Samples samples) {
        if (samples.validCount == 0) {
            return null;
        }
        final double[][] validX = Arrays.copyOf(samples.validX, samples.validCount);
        final double[] predicted = model.predict(validX).point();
        double sum = 0.0;
        for (int i = 0; i < samples.validCount; i++) {
            sum += Math.abs(predicted[i] - samples.validY[i]);
        }
        return sum / samples.validCount * 100.0;
    }

    /**
     * Sagt je Tankstelle die volle Kurve voraus, rechnet die Selbstkorrektur gegen und verdichtet zu
     * Tageskennzahlen samt Tanktipp.
     *
     * @param ctx             Kontext
     * @param model           trainiertes Modell
     * @param biasByStation   Selbstkorrektur je Tankstelle
     * @param horizonSteps    größter Horizont in Rasterschritten
     * @param stepSeconds     Rasterabstand in Sekunden
     * @param minHistorySteps Mindesthistorie in Rasterschritten
     * @return Vorhersage je Tankstelle
     */
    private List<StationForecast> infer(final ForecastContext ctx, final GbdtForecastModel model,
                                        final Map<String, double[]> biasByStation, final int horizonSteps,
                                        final long stepSeconds, final int minHistorySteps) {
        final List<StationForecast> stations = new ArrayList<>();
        for (final String stationId : ctx.stationIds()) {
            final HourlyGrid grid = ctx.grid(stationId);
            if (grid.size() < minHistorySteps) {
                continue;
            }
            final int origin = grid.lastIndex();
            final long originEpoch = grid.timeAt(origin);
            final double[][] features = new double[horizonSteps][];
            for (int h = 1; h <= horizonSteps; h++) {
                features[h - 1] = ForecastFeatures.build(ctx, stationId, origin, h);
            }
            final GbdtForecastModel.Prediction prediction = model.predict(features);
            final double[] bias = biasByStation.get(stationId);
            final List<CurvePoint> curve = buildCurve(prediction, bias, originEpoch, stepSeconds, horizonSteps);
            final List<DailyPoint> dailies = buildDailies(curve, grid.lastPrice(), originEpoch);
            stations.add(new StationForecast(stationId, curve, dailies));
        }
        return stations;
    }

    /**
     * Baut die Kurvenpunkte einer Tankstelle samt gegengerechneter Selbstkorrektur.
     *
     * @param prediction   Modellvorhersage über alle Horizonte
     * @param bias         Selbstkorrektur (Cent je Horizont-Abschnitt) oder {@code null}
     * @param originEpoch  Ausgangszeitpunkt (Sekunden seit der Epoche)
     * @param stepSeconds  Rasterabstand in Sekunden
     * @param horizonSteps größter Horizont in Rasterschritten
     * @return Kurvenpunkte
     */
    private List<CurvePoint> buildCurve(final GbdtForecastModel.Prediction prediction, final double[] bias,
                                        final long originEpoch, final long stepSeconds, final int horizonSteps) {
        final List<CurvePoint> curve = new ArrayList<>(horizonSteps);
        for (int h = 1; h <= horizonSteps; h++) {
            final int idx = h - 1;
            final int horizonMinutes = (int) ((long) h * stepSeconds / 60L);
            final double correction = bias == null ? 0.0 : bias[bucketFor(horizonMinutes)] / 100.0;
            final double point = clamp(prediction.point()[idx] - correction);
            final Double low = prediction.low() == null ? null : clamp(prediction.low()[idx] - correction);
            final Double high = prediction.high() == null ? null : clamp(prediction.high()[idx] - correction);
            final Instant targetAt = Instant.ofEpochSecond(originEpoch + (long) h * stepSeconds);
            curve.add(new CurvePoint(targetAt, horizonMinutes, point, low, high));
        }
        return curve;
    }

    /**
     * Verdichtet die Kurve zu Tageskennzahlen und setzt den Tanktipp für den heutigen Tag.
     *
     * @param curve       Kurvenpunkte
     * @param current     aktueller Preis (Ausgangspreis)
     * @param originEpoch Ausgangszeitpunkt (Sekunden seit der Epoche)
     * @return Tageskennzahlen
     */
    private List<DailyPoint> buildDailies(final List<CurvePoint> curve, final double current, final long originEpoch) {
        final LocalDate today = Instant.ofEpochSecond(originEpoch).atZone(BERLIN).toLocalDate();
        final List<LocalDate> order = new ArrayList<>();
        final Map<LocalDate, List<CurvePoint>> byDate = new java.util.LinkedHashMap<>();
        for (final CurvePoint point : curve) {
            final LocalDate date = point.targetAt().atZone(BERLIN).toLocalDate();
            byDate.computeIfAbsent(date, key -> {
                order.add(key);
                return new ArrayList<>();
            }).add(point);
        }
        final List<DailyPoint> dailies = new ArrayList<>();
        for (final LocalDate date : order) {
            dailies.add(summarizeDate(date, byDate.get(date), current, today, curve, originEpoch));
        }
        return dailies;
    }

    /**
     * Bildet die Tageskennzahlen eines Vorhersagetages und – nur für heute – den Tanktipp.
     *
     * @param date        Vorhersagetag
     * @param points      Kurvenpunkte dieses Tages
     * @param current     aktueller Preis
     * @param today       heutiger Tag
     * @param fullCurve   gesamte Kurve (für das im Wartefenster erreichbare Tief)
     * @param originEpoch Ausgangszeitpunkt (Sekunden seit der Epoche)
     * @return Tageskennzahl
     */
    private DailyPoint summarizeDate(final LocalDate date, final List<CurvePoint> points, final double current,
                                     final LocalDate today, final List<CurvePoint> fullCurve, final long originEpoch) {
        CurvePoint low = points.get(0);
        double sum = 0.0;
        for (final CurvePoint point : points) {
            if (point.predicted() < low.predicted()) {
                low = point;
            }
            sum += point.predicted();
        }
        final double mean = sum / points.size();
        RefuelRecommendation recommendation = null;
        String reason = null;
        Double savingCt = null;
        Double currentAmount = null;
        if (date.equals(today)) {
            final long maxWaitSeconds = (long) props.getRecommendationMaxWaitMinutes() * 60L;
            double reachableLow = current;
            for (final CurvePoint point : fullCurve) {
                if (point.targetAt().getEpochSecond() - originEpoch <= maxWaitSeconds
                        && point.predicted() < reachableLow) {
                    reachableLow = point.predicted();
                }
            }
            final double saving = (current - reachableLow) * 100.0;
            final boolean wait = saving >= props.getRecommendationThresholdCt();
            recommendation = wait ? RefuelRecommendation.WARTEN : RefuelRecommendation.TANKEN;
            reason = wait ? "TIEF_IM_WARTEFENSTER" : "JETZT_GUENSTIG";
            savingCt = Math.max(0.0, saving);
            currentAmount = current;
        }
        return new DailyPoint(date, low.predicted(), low.targetAt(), mean, low.low(), low.high(),
                recommendation, reason, savingCt, currentAmount);
    }

    /**
     * Ordnet einen Horizont (Minuten) seinem Abschnitt zu (0 = bis 2 h, 1 = bis 6 h, 2 = bis 12 h, 3 = darüber).
     *
     * @param horizonMinutes Horizont in Minuten
     * @return Abschnittsnummer 0–3
     */
    static int bucketFor(final int horizonMinutes) {
        if (horizonMinutes <= 120) {
            return 0;
        }
        if (horizonMinutes <= 360) {
            return 1;
        }
        if (horizonMinutes <= 720) {
            return 2;
        }
        return 3;
    }

    /**
     * Begrenzt einen Preis auf den plausiblen Wertebereich.
     *
     * @param value Preis
     * @return begrenzter Preis
     */
    private static double clamp(final double value) {
        return Math.min(MAX_PRICE, Math.max(MIN_PRICE, value));
    }

    /**
     * Rundet einen Zeitpunkt auf das nächstniedrigere Vielfache des Rasterabstands ab.
     *
     * @param epochSecond Zeitpunkt in Sekunden seit der Epoche
     * @param stepSeconds Rasterabstand in Sekunden
     * @return abgerundeter Rasterzeitpunkt
     */
    private static long floorToStep(final long epochSecond, final long stepSeconds) {
        return Math.floorDiv(epochSecond, stepSeconds) * stepSeconds;
    }

    /**
     * Veränderliche Sammelstruktur der Trainings- und Validierungsmatrizen.
     */
    private static final class Samples {

        /** Merkmalsmatrix der Trainingsbeispiele. */
        private final double[][] trainX;

        /** Zielwerte der Trainingsbeispiele. */
        private final double[] trainY;

        /** Merkmalsmatrix der Validierungsbeispiele. */
        private final double[][] validX;

        /** Zielwerte der Validierungsbeispiele. */
        private final double[] validY;

        /** Anzahl der gefüllten Trainingsbeispiele. */
        private int trainCount;

        /** Anzahl der gefüllten Validierungsbeispiele. */
        private int validCount;

        /**
         * Legt die Sammelstruktur mit den vorab bestimmten Kapazitäten an.
         *
         * @param trainCapacity Feldgröße der Trainingsbeispiele
         * @param validCapacity Feldgröße der Validierungsbeispiele
         */
        private Samples(final int trainCapacity, final int validCapacity) {
            this.trainX = new double[trainCapacity][];
            this.trainY = new double[trainCapacity];
            this.validX = new double[validCapacity][];
            this.validY = new double[validCapacity];
        }

    }

    /**
     * Vorhersage-Ergebnis einer Kraftstoffart über alle Tankstellen.
     *
     * @param fuelCode          Datenbank-Code des Kraftstoffs
     * @param generatedAt       Erzeugungszeitpunkt
     * @param originAt          Ausgangszeitpunkt der Vorhersage
     * @param horizonHours      Vorhersagehorizont in Stunden
     * @param resolutionMinutes Auflösung in Minuten
     * @param modelVersion      Modellversion
     * @param trainRows         Anzahl der genutzten Trainingszeilen
     * @param trainMae          mittlerer absoluter Validierungsfehler (Cent/Liter) oder {@code null}
     * @param stations          Vorhersage je Tankstelle
     */
    public record Result(String fuelCode, Instant generatedAt, Instant originAt, int horizonHours,
                         int resolutionMinutes, String modelVersion, int trainRows, Double trainMae,
                         List<StationForecast> stations) {

    }

    /**
     * Vorhersage einer einzelnen Tankstelle.
     *
     * @param stationId Kennung der Tankstelle
     * @param curve     feinkörnige Vorhersagekurve
     * @param dailies   verdichtete Tageskennzahlen
     */
    public record StationForecast(String stationId, List<CurvePoint> curve, List<DailyPoint> dailies) {

    }

    /**
     * Ein vorhergesagter Kurvenpunkt.
     *
     * @param targetAt       Zielzeitpunkt
     * @param horizonMinutes Horizont in Minuten
     * @param predicted      Punktvorhersage (Euro/Liter)
     * @param low            unteres Band (q10) oder {@code null}
     * @param high           oberes Band (q90) oder {@code null}
     */
    public record CurvePoint(Instant targetAt, int horizonMinutes, double predicted, Double low, Double high) {

    }

    /**
     * Verdichtete Tageskennzahl einer Vorhersage.
     *
     * @param date           Vorhersagetag
     * @param low            prognostiziertes Tagestief (Euro/Liter)
     * @param lowAt          Zeitpunkt des Tagestiefs
     * @param mean           prognostiziertes Tagesmittel (Euro/Liter)
     * @param lowQ10         unteres Band des Tagestiefs oder {@code null}
     * @param lowQ90         oberes Band des Tagestiefs oder {@code null}
     * @param recommendation Tanktipp (nur für heute gesetzt) oder {@code null}
     * @param reason         Kurzbegründung des Tanktipps oder {@code null}
     * @param savingCt       erwartete Ersparnis (Cent/Liter) oder {@code null}
     * @param currentAmount  aktueller Preis (nur für heute gesetzt) oder {@code null}
     */
    public record DailyPoint(LocalDate date, double low, Instant lowAt, double mean, Double lowQ10,
                             Double lowQ90, RefuelRecommendation recommendation, String reason,
                             Double savingCt, Double currentAmount) {

    }

}
