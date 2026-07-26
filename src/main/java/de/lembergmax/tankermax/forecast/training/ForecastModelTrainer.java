package de.lembergmax.tankermax.forecast.training;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import de.lembergmax.tankermax.forecast.domain.RefuelRecommendation;
import de.lembergmax.tankermax.forecast.ml.ForecastContext;
import de.lembergmax.tankermax.forecast.ml.ForecastFeatures;
import de.lembergmax.tankermax.forecast.ml.GbdtForecastModel;
import de.lembergmax.tankermax.forecast.ml.HourlyGrid;
import de.lembergmax.tankermax.forecast.ml.TrainedModel;
import de.lembergmax.tankermax.forecast.ml.TrainingMatrix;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Trainiert das Vorhersagemodell einer Kraftstoffart und erzeugt daraus die Vorhersagekurve samt
 * Tageszusammenfassungen.
 *
 * <p>Zielgröße des Modells ist die <em>Preisänderung</em> gegenüber dem Ausgangspreis, nicht der
 * Preis selbst. Der Horizont ist dabei ein Merkmal, sodass ein gemeinsames Modell je Kraftstoffart
 * dennoch eine individuelle Kurve je Tankstelle liefert. Bei der Inferenz wird der Ausgangspreis
 * wieder aufaddiert.</p>
 *
 * <p>Die Trainingsstichprobe deckt standardmäßig jeden Rasterpunkt als Ausgangszeitpunkt und jeden
 * ausgelieferten Horizont ab. Übersteigt das die konfigurierte Zeilenzahl, wird <em>zufällig</em> mit
 * festem Startwert ausgedünnt. Ein gleichmäßiges „jedes n-te Beispiel“ wäre hier fehlerhaft: Da die
 * innere Schleife über eine feste Zahl von Horizonten läuft, bliebe die Phase über alle Blöcke
 * erhalten und es überlebten immer dieselben wenigen Horizonte.</p>
 *
 * <p>Die jüngsten Tage bilden den Validierungsausschnitt. Beispiele, deren Zielzeitpunkt dort
 * hineinreicht, werden zusätzlich aus dem Training entfernt (Sperrzone), damit der gemessene Fehler
 * nicht beschönigt wird. Ist {@code refit-on-full-data} aktiv, dient dieser Schnitt nur der Messung
 * und das ausgelieferte Modell wird anschließend über den gesamten Zeitraum neu gebildet.</p>
 *
 * <p>Reine Rechenarbeit ohne Datenbankzugriff; nur im Profil {@code ingest} aktiv.</p>
 */
@Service
@Profile("ingest")
@RequiredArgsConstructor
public class ForecastModelTrainer {

    /** Logger dieser Klasse. */
    private static final Logger LOG = LoggerFactory.getLogger(ForecastModelTrainer.class);

    /** Zeitzone, in der Vorhersagetage und Tanktipp bestimmt werden. */
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Plausibler kleinster Literpreis (Euro); Vorhersagen werden darauf begrenzt. */
    private static final double MIN_PRICE = 0.5;

    /** Plausibler größter Literpreis (Euro); Vorhersagen werden darauf begrenzt. */
    private static final double MAX_PRICE = 3.0;

    /** Sekunden je Tag. */
    private static final long SECONDS_PER_DAY = 86_400L;

    /** Cent-Faktor zur Umrechnung eines Euro-Betrags. */
    private static final double CENT_FACTOR = 100.0;

    /**
     * Vorlauf in Stunden, bevor ein Rasterpunkt als Ausgangszeitpunkt dient. Entspricht dem längsten
     * Verzögerungswert (eine Woche): Davor wäre der Wochen-Lag am Rasteranfang abgeschnitten und würde
     * fälschlich eine unveränderte Woche vortäuschen.
     */
    private static final int WARMUP_HOURS = 168;

    /** Obergrenze der Validierungszeilen. */
    private static final int VALIDATION_CAP = 200_000;

    /** Beschriftungen der Horizont-Abschnitte für die Fehlerausgabe. */
    private static final String[] BUCKET_LABELS = {"<=2h", "<=6h", "<=12h", ">12h"};

    /** Konfiguration der Vorhersage. */
    private final ForecastProperties props;

    /**
     * Trainiert das Modell einer Kraftstoffart.
     *
     * @param fuelCode Datenbank-Code des Kraftstoffs (nur für die Protokollausgabe)
     * @param ctx      vorberechneter Kontext aller Tankstellen
     * @param now      Zeitpunkt dieses Trainingslaufs
     * @return trainiertes Modell oder {@code null}, wenn zu wenige Trainingsdaten vorliegen
     */
    public TrainedModel train(final String fuelCode, final ForecastContext ctx, final Instant now) {
        final Layout layout = Layout.of(ctx, props);
        final long cutoff = now.getEpochSecond() - (long) props.getValidationDays() * SECONDS_PER_DAY;
        final long[] totals = count(ctx, layout, cutoff);
        final long trainTotal = totals[0];
        final long validTotal = totals[1];
        final long fullTotal = totals[2];
        if (fullTotal == 0) {
            return null;
        }

        Double mae = null;
        if (trainTotal > 0 && validTotal > 0) {
            mae = measure(fuelCode, ctx, layout, cutoff, trainTotal, validTotal);
        }

        final boolean refit = props.isRefitOnFullData() || mae == null;
        final Part shippedPart = refit ? Part.FULL : Part.TRAIN;
        final long shippedTotal = refit ? fullTotal : trainTotal;
        final TrainingMatrix matrix = collect(ctx, layout, cutoff, shippedPart, shippedTotal,
                props.getMaxTrainRows());
        if (matrix.isEmpty()) {
            return null;
        }
        final int trainRows = matrix.rows();
        LOG.info("Vorhersage {}: Modelltraining auf {} Zeilen ({}).", fuelCode, trainRows,
                refit ? "gesamter Zeitraum" : "ohne Validierungsausschnitt");
        final GbdtForecastModel model = GbdtForecastModel.train(matrix, props, true);
        return new TrainedModel(model, now, String.valueOf(now.getEpochSecond()), trainRows, mae);
    }

    /**
     * Erzeugt die Vorhersagekurve und die Tageszusammenfassungen aus einem trainierten Modell.
     *
     * @param fuelCode      Datenbank-Code des Kraftstoffs
     * @param ctx           vorberechneter Kontext aller Tankstellen
     * @param trained       trainiertes Modell samt Kennzahlen
     * @param biasByStation Selbstkorrektur je Tankstelle (Cent je Horizont-Abschnitt) oder leer
     * @param now           Zeitpunkt dieses Vorhersagelaufs
     * @return Vorhersage-Ergebnis
     */
    public Result infer(final String fuelCode, final ForecastContext ctx, final TrainedModel trained,
                        final Map<String, double[]> biasByStation, final Instant now) {
        final Layout layout = Layout.of(ctx, props);
        final List<StationForecast> stations = new ArrayList<>();
        for (final String stationId : ctx.stationIds()) {
            final HourlyGrid grid = ctx.grid(stationId);
            if (grid.size() < layout.minHistorySteps()) {
                continue;
            }
            final int origin = grid.lastIndex();
            final double current = grid.priceAt(origin);
            final double[][] features = new double[layout.horizonSteps()][];
            for (int h = 1; h <= layout.horizonSteps(); h++) {
                features[h - 1] = ForecastFeatures.build(ctx, stationId, origin, h);
            }
            final GbdtForecastModel.Prediction prediction = trained.model().predict(features);
            final List<CurvePoint> curve = buildCurve(prediction, biasByStation.get(stationId), current,
                    grid.timeAt(origin), ctx.stepSeconds(), layout.horizonSteps());
            stations.add(new StationForecast(stationId, curve,
                    buildDailies(curve, current, grid.timeAt(origin))));
        }
        final long originEpoch = floorToStep(now.getEpochSecond(), ctx.stepSeconds());
        return new Result(fuelCode, now, Instant.ofEpochSecond(originEpoch), props.getHorizonHours(),
                props.getResolutionMinutes(), trained.modelVersion(), trained.trainRows(),
                trained.trainMae(), stations);
    }

    /**
     * Trainiert ein Messmodell ohne den Validierungsausschnitt und bestimmt daran den mittleren
     * absoluten Fehler, aufgeschlüsselt nach Horizont-Abschnitt.
     *
     * @param fuelCode   Datenbank-Code des Kraftstoffs
     * @param ctx        Kontext
     * @param layout     Rastermaße des Laufs
     * @param cutoff     Beginn des Validierungsausschnitts
     * @param trainTotal Anzahl der Trainingskandidaten
     * @param validTotal Anzahl der Validierungskandidaten
     * @return mittlerer absoluter Fehler in Cent/Liter oder {@code null}
     */
    private Double measure(final String fuelCode, final ForecastContext ctx, final Layout layout,
                           final long cutoff, final long trainTotal, final long validTotal) {
        final TrainingMatrix matrix = collect(ctx, layout, cutoff, Part.TRAIN, trainTotal,
                props.getMaxTrainRows());
        if (matrix.isEmpty()) {
            return null;
        }
        final GbdtForecastModel probe = GbdtForecastModel.train(matrix, props, false);
        final Validation validation = collectValidation(ctx, layout, cutoff, validTotal);
        if (validation.rows == 0) {
            return null;
        }
        final double[] predicted = probe.predict(validation.features).point();
        final double[] bucketSum = new double[BUCKET_LABELS.length];
        final int[] bucketCount = new int[BUCKET_LABELS.length];
        double sum = 0.0;
        for (int row = 0; row < validation.rows; row++) {
            final double error = Math.abs(predicted[row] - validation.targets[row]) * CENT_FACTOR;
            sum += error;
            final int bucket = bucketFor(validation.horizonMinutes[row]);
            bucketSum[bucket] += error;
            bucketCount[bucket]++;
        }
        logBuckets(fuelCode, validation.rows, bucketSum, bucketCount);
        return sum / validation.rows;
    }

    /**
     * Protokolliert den Validierungsfehler je Horizont-Abschnitt.
     *
     * @param fuelCode    Datenbank-Code des Kraftstoffs
     * @param rows        Anzahl der Validierungszeilen
     * @param bucketSum   Fehlersumme je Abschnitt
     * @param bucketCount Zeilenzahl je Abschnitt
     */
    private void logBuckets(final String fuelCode, final int rows, final double[] bucketSum,
                            final int[] bucketCount) {
        final StringBuilder text = new StringBuilder();
        for (int bucket = 0; bucket < BUCKET_LABELS.length; bucket++) {
            if (bucketCount[bucket] == 0) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(String.format("%s %.3f ct", BUCKET_LABELS[bucket],
                    bucketSum[bucket] / bucketCount[bucket]));
        }
        LOG.info("Vorhersage {}: Validierung auf {} Zeilen – MAE je Horizont: {}", fuelCode, rows, text);
    }

    /**
     * Zählt die Kandidaten je Stichprobenteil.
     *
     * @param ctx    Kontext
     * @param layout Rastermaße des Laufs
     * @param cutoff Beginn des Validierungsausschnitts
     * @return Array {Trainingskandidaten, Validierungskandidaten, Kandidaten insgesamt}
     */
    private long[] count(final ForecastContext ctx, final Layout layout, final long cutoff) {
        final long[] totals = new long[3];
        for (final String stationId : ctx.stationIds()) {
            final HourlyGrid grid = ctx.grid(stationId);
            if (grid.size() < layout.minHistorySteps()) {
                continue;
            }
            final int last = grid.lastIndex();
            for (int origin = layout.warmupSteps(); origin <= last - 1; origin += layout.originStep()) {
                final long originEpoch = grid.timeAt(origin);
                for (int h = layout.horizonStep(); h <= layout.horizonSteps(); h += layout.horizonStep()) {
                    if (origin + h > last) {
                        break;
                    }
                    totals[2]++;
                    if (originEpoch >= cutoff) {
                        totals[1]++;
                    } else if (grid.timeAt(origin + h) < cutoff) {
                        totals[0]++;
                    }
                }
            }
        }
        return totals;
    }

    /**
     * Sammelt einen Stichprobenteil in eine spaltenweise Trainingsmatrix und dünnt ihn dabei zufällig
     * auf die zulässige Zeilenzahl aus.
     *
     * @param ctx    Kontext
     * @param layout Rastermaße des Laufs
     * @param cutoff Beginn des Validierungsausschnitts
     * @param part   zu sammelnder Teil
     * @param total  Anzahl der Kandidaten dieses Teils
     * @param cap    Höchstzahl zu übernehmender Zeilen
     * @return gefüllte Trainingsmatrix
     */
    private TrainingMatrix collect(final ForecastContext ctx, final Layout layout, final long cutoff,
                                   final Part part, final long total, final long cap) {
        final TrainingMatrix matrix = new TrainingMatrix(ForecastFeatures.count(), capacityFor(total, cap));
        final double acceptance = total <= cap ? 1.0 : (double) cap / total;
        final Random random = new Random(props.getSamplingSeed() + part.ordinal());
        final double[] buffer = new double[ForecastFeatures.count()];
        for (final String stationId : ctx.stationIds()) {
            final HourlyGrid grid = ctx.grid(stationId);
            if (grid.size() < layout.minHistorySteps()) {
                continue;
            }
            final int last = grid.lastIndex();
            for (int origin = layout.warmupSteps(); origin <= last - 1; origin += layout.originStep()) {
                final long originEpoch = grid.timeAt(origin);
                final double current = grid.priceAt(origin);
                for (int h = layout.horizonStep(); h <= layout.horizonSteps(); h += layout.horizonStep()) {
                    if (origin + h > last) {
                        break;
                    }
                    if (!part.accepts(originEpoch, grid.timeAt(origin + h), cutoff)) {
                        continue;
                    }
                    if (acceptance < 1.0 && random.nextDouble() >= acceptance) {
                        continue;
                    }
                    ForecastFeatures.build(ctx, stationId, origin, h, buffer);
                    if (!matrix.add(buffer, grid.priceAt(origin + h) - current)) {
                        return matrix;
                    }
                }
            }
        }
        return matrix;
    }

    /**
     * Sammelt den Validierungsausschnitt zeilenweise samt Horizont, um den Fehler je Abschnitt
     * ausweisen zu können.
     *
     * @param ctx    Kontext
     * @param layout Rastermaße des Laufs
     * @param cutoff Beginn des Validierungsausschnitts
     * @param total  Anzahl der Validierungskandidaten
     * @return gefüllter Validierungsausschnitt
     */
    private Validation collectValidation(final ForecastContext ctx, final Layout layout, final long cutoff,
                                         final long total) {
        final int capacity = capacityFor(total, VALIDATION_CAP);
        final Validation validation = new Validation(capacity);
        final double acceptance = total <= VALIDATION_CAP ? 1.0 : (double) VALIDATION_CAP / total;
        final Random random = new Random(props.getSamplingSeed() + Part.VALIDATION.ordinal());
        for (final String stationId : ctx.stationIds()) {
            final HourlyGrid grid = ctx.grid(stationId);
            if (grid.size() < layout.minHistorySteps()) {
                continue;
            }
            final int last = grid.lastIndex();
            for (int origin = layout.warmupSteps(); origin <= last - 1; origin += layout.originStep()) {
                final long originEpoch = grid.timeAt(origin);
                if (originEpoch < cutoff) {
                    continue;
                }
                final double current = grid.priceAt(origin);
                for (int h = layout.horizonStep(); h <= layout.horizonSteps(); h += layout.horizonStep()) {
                    if (origin + h > last) {
                        break;
                    }
                    if (acceptance < 1.0 && random.nextDouble() >= acceptance) {
                        continue;
                    }
                    if (validation.rows == capacity) {
                        return validation;
                    }
                    validation.features[validation.rows] = ForecastFeatures.build(ctx, stationId, origin, h);
                    validation.targets[validation.rows] = grid.priceAt(origin + h) - current;
                    validation.horizonMinutes[validation.rows] =
                            (int) ((long) h * ctx.stepSeconds() / 60L);
                    validation.rows++;
                }
            }
        }
        return validation;
    }

    /**
     * Baut die Kurvenpunkte einer Tankstelle: Die vorhergesagte Änderung wird auf den Ausgangspreis
     * addiert, die gemessene Selbstkorrektur gegengerechnet und das Ergebnis begrenzt.
     *
     * @param prediction   Modellvorhersage (Preisänderungen) über alle Horizonte
     * @param bias         Selbstkorrektur (Cent je Horizont-Abschnitt) oder {@code null}
     * @param current      Ausgangspreis
     * @param originEpoch  Ausgangszeitpunkt (Sekunden seit der Epoche)
     * @param stepSeconds  Rasterabstand in Sekunden
     * @param horizonSteps größter Horizont in Rasterschritten
     * @return Kurvenpunkte
     */
    private List<CurvePoint> buildCurve(final GbdtForecastModel.Prediction prediction, final double[] bias,
                                        final double current, final long originEpoch, final long stepSeconds,
                                        final int horizonSteps) {
        final List<CurvePoint> curve = new ArrayList<>(horizonSteps);
        for (int h = 1; h <= horizonSteps; h++) {
            final int idx = h - 1;
            final int horizonMinutes = (int) ((long) h * stepSeconds / 60L);
            final double correction = bias == null ? 0.0 : bias[bucketFor(horizonMinutes)] / CENT_FACTOR;
            final double point = clamp(current + prediction.point()[idx] - correction);
            final Double low = prediction.low() == null
                    ? null : clamp(current + prediction.low()[idx] - correction);
            final Double high = prediction.high() == null
                    ? null : clamp(current + prediction.high()[idx] - correction);
            curve.add(new CurvePoint(Instant.ofEpochSecond(originEpoch + (long) h * stepSeconds),
                    horizonMinutes, point, low, high));
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
    private List<DailyPoint> buildDailies(final List<CurvePoint> curve, final double current,
                                          final long originEpoch) {
        final LocalDate today = Instant.ofEpochSecond(originEpoch).atZone(BERLIN).toLocalDate();
        final List<LocalDate> order = new ArrayList<>();
        final Map<LocalDate, List<CurvePoint>> byDate = new LinkedHashMap<>();
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
                                     final LocalDate today, final List<CurvePoint> fullCurve,
                                     final long originEpoch) {
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
            final double saving = (current - reachableLow) * CENT_FACTOR;
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
     * Bestimmt die Feldgröße einer Stichprobe samt Sicherheitszuschlag.
     *
     * <p>Bei der zufälligen Auswahl schwankt die tatsächlich getroffene Zeilenzahl um den Zielwert.
     * Der Zuschlag von acht Standardabweichungen sorgt dafür, dass das Feld praktisch nie vorzeitig
     * volläuft und damit spätere Tankstellen benachteiligt.</p>
     *
     * @param total Anzahl der Kandidaten
     * @param cap   Zielzahl der Zeilen
     * @return Feldgröße
     */
    private static int capacityFor(final long total, final long cap) {
        if (total <= cap) {
            return (int) total;
        }
        final long margin = Math.max(1_000L, (long) (8.0 * Math.sqrt(cap)));
        return (int) Math.min(total, cap + margin);
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
     * Teil der Stichprobe, der gesammelt werden soll.
     */
    private enum Part {

        /** Training ohne den Validierungsausschnitt und ohne die Beispiele, die dorthin reichen. */
        TRAIN,

        /** Der Validierungsausschnitt selbst. */
        VALIDATION,

        /** Der gesamte Zeitraum, für das ausgelieferte Modell. */
        FULL;

        /**
         * Prüft, ob ein Kandidat zu diesem Teil gehört.
         *
         * @param originEpoch Ausgangszeitpunkt
         * @param targetEpoch Zielzeitpunkt
         * @param cutoff      Beginn des Validierungsausschnitts
         * @return {@code true}, wenn der Kandidat zu diesem Teil zählt
         */
        private boolean accepts(final long originEpoch, final long targetEpoch, final long cutoff) {
            return switch (this) {
                case TRAIN -> originEpoch < cutoff && targetEpoch < cutoff;
                case VALIDATION -> originEpoch >= cutoff;
                case FULL -> true;
            };
        }

    }

    /**
     * Aus der Konfiguration abgeleitete Rastermaße eines Laufs.
     *
     * @param horizonSteps    größter Horizont in Rasterschritten
     * @param originStep      Abstand der Ausgangszeitpunkte in Rasterschritten
     * @param horizonStep     Abstand der Horizonte in Rasterschritten
     * @param warmupSteps     Vorlauf in Rasterschritten, bevor ein Punkt als Ausgang dient
     * @param minHistorySteps Mindesthistorie in Rasterschritten
     */
    private record Layout(int horizonSteps, int originStep, int horizonStep, int warmupSteps,
                          int minHistorySteps) {

        /**
         * Leitet die Rastermaße aus Kontext und Konfiguration ab.
         *
         * @param ctx   Kontext
         * @param props Konfiguration
         * @return Rastermaße
         */
        private static Layout of(final ForecastContext ctx, final ForecastProperties props) {
            final int sph = ctx.stepsPerHour();
            return new Layout(props.getHorizonHours() * sph,
                    Math.max(1, props.getTrainOriginStepHours() * sph),
                    Math.max(1, props.getTrainHorizonStepHours() * sph),
                    WARMUP_HOURS * sph,
                    props.getMinHistoryDays() * 24 * sph);
        }

    }

    /**
     * Zeilenweise gesammelter Validierungsausschnitt.
     */
    private static final class Validation {

        /** Merkmalsmatrix der Validierungsbeispiele. */
        private final double[][] features;

        /** Zielwerte (Preisänderungen) der Validierungsbeispiele. */
        private final double[] targets;

        /** Horizont je Validierungsbeispiel in Minuten. */
        private final int[] horizonMinutes;

        /** Anzahl der gefüllten Zeilen. */
        private int rows;

        /**
         * Legt den Ausschnitt mit fester Kapazität an.
         *
         * @param capacity Höchstzahl aufnehmbarer Zeilen
         */
        private Validation(final int capacity) {
            this.features = new double[capacity][];
            this.targets = new double[capacity];
            this.horizonMinutes = new int[capacity];
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
