package de.lembergmax.tankermax.forecast.ml;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import smile.base.cart.Loss;
import smile.data.DataFrame;
import smile.data.Tuple;
import smile.data.formula.Formula;
import smile.regression.GradientTreeBoost;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * Gradient-Boosting-Modell (Smile) für die Preisvorhersage einer Kraftstoffart.
 *
 * <p>Vorhergesagt wird nicht der Preis selbst, sondern seine <em>Änderung</em> gegenüber dem
 * Ausgangspreis (siehe {@link de.lembergmax.tankermax.forecast.training.ForecastModelTrainer}).
 * Entscheidungsbäume sind stückweise konstant und können nicht extrapolieren; auf das absolute
 * Preisniveau trainiert müssten sie es über Schnitte am aktuellen Preis nachbilden und würden zum
 * Median des Trainingsfensters zurückfallen, sobald das Niveau daraus herausläuft. Auf die Änderung
 * trainiert ist die Zielgröße dagegen stationär und um null zentriert.</p>
 *
 * <p>Es kapselt bis zu drei Booster: das Hauptmodell minimiert den mittleren absoluten Fehler
 * ({@link Loss#lad()}), die beiden optionalen Quantil-Modelle ({@link Loss#quantile(double)})
 * schätzen das untere und obere Band der Unsicherheit. Sämtliche Bäume sind reines Java und
 * benötigen keine nativen Bibliotheken, was den Einsatz auf dem Raspberry Pi ermöglicht.</p>
 *
 * <p>Das Modell ist serialisierbar, damit das aufwendige Training nur alle paar Tage laufen muss,
 * während die Vorhersagekurve täglich mit frischen Preisen neu gerechnet wird. Die beim Training
 * gültigen Merkmalsnamen werden mitgeführt: Ändert sich der Merkmalsvektor durch ein Programm-Update,
 * lässt sich ein gespeichertes Modell so als unverwendbar erkennen.</p>
 */
public final class GbdtForecastModel implements Serializable {

    /** Serialisierungskennung. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** Name der Zielspalte im Trainings-Datensatz. */
    private static final String TARGET = "target";

    /** Hauptmodell (Punktvorhersage der Preisänderung). */
    private final GradientTreeBoost main;

    /** Quantil-Modell für das untere Band; {@code null}, wenn keine Quantile berechnet werden. */
    private final GradientTreeBoost low;

    /** Quantil-Modell für das obere Band; {@code null}, wenn keine Quantile berechnet werden. */
    private final GradientTreeBoost high;

    /** Spaltennamen des Datensatzes (Merkmale gefolgt von der Zielspalte). */
    private final String[] columnNames;

    /**
     * Erzeugt das Modell aus den trainierten Boostern.
     *
     * @param main        Hauptmodell
     * @param low         Quantil-Modell des unteren Bandes oder {@code null}
     * @param high        Quantil-Modell des oberen Bandes oder {@code null}
     * @param columnNames Spaltennamen des Datensatzes
     */
    private GbdtForecastModel(final GradientTreeBoost main, final GradientTreeBoost low,
                              final GradientTreeBoost high, final String[] columnNames) {
        this.main = main;
        this.low = low;
        this.high = high;
        this.columnNames = columnNames;
    }

    /**
     * Liefert die Spaltennamen, die zum Zeitpunkt des Trainings gültig waren.
     *
     * @return Spaltennamen (Merkmale gefolgt von der Zielspalte)
     */
    public static String[] columnNames() {
        final String[] featureNames = ForecastFeatures.featureNames();
        final String[] names = new String[featureNames.length + 1];
        System.arraycopy(featureNames, 0, names, 0, featureNames.length);
        names[featureNames.length] = TARGET;
        return names;
    }

    /**
     * Prüft, ob das Modell zum aktuellen Merkmalsvektor passt.
     *
     * @return {@code true}, wenn die Merkmalsnamen unverändert sind
     */
    public boolean matchesCurrentFeatures() {
        return Arrays.equals(columnNames, columnNames());
    }

    /**
     * Trainiert das Modell aus der spaltenweise gesammelten Trainingsmatrix.
     *
     * <p>Die Matrix wird dabei verbraucht: Ihre Spalten gehen ohne Kopie in den Smile-Datensatz über.</p>
     *
     * @param matrix         gesammelte Trainingsbeispiele (Merkmale und Zielspalte)
     * @param props          Hyperparameter und Quantil-Einstellungen
     * @param withQuantiles  {@code true}, wenn zusätzlich die beiden Quantil-Modelle zu trainieren sind
     * @return das trainierte Modell
     */
    public static GbdtForecastModel train(final TrainingMatrix matrix, final ForecastProperties props,
                                          final boolean withQuantiles) {
        final String[] columnNames = columnNames();
        final DataFrame data = matrix.toDataFrame(columnNames);
        final Formula formula = Formula.lhs(TARGET);

        final GradientTreeBoost main = fit(formula, data, Loss.lad(), props);
        GradientTreeBoost low = null;
        GradientTreeBoost high = null;
        if (withQuantiles && props.isQuantilesEnabled()) {
            low = fit(formula, data, Loss.quantile(props.getQuantileLow()), props);
            high = fit(formula, data, Loss.quantile(props.getQuantileHigh()), props);
        }
        return new GbdtForecastModel(main, low, high, columnNames);
    }

    /**
     * Sagt die Punktvorhersage sowie – falls vorhanden – das untere und obere Band voraus.
     *
     * <p>Alle Werte sind Preis<em>änderungen</em> gegenüber dem Ausgangspreis. Die Band-Grenzen werden
     * monoton erzwungen, sodass stets {@code low ≤ Punkt ≤ high} gilt.</p>
     *
     * @param features Merkmalsmatrix der vorherzusagenden Datenpunkte (zeilenweise)
     * @return Vorhersage mit Punktwerten und – sofern trainiert – Bandgrenzen
     */
    public Prediction predict(final double[][] features) {
        final int rows = features.length;
        final DataFrame frame = toDataFrame(features);
        final double[] point = new double[rows];
        final double[] lowOut = low == null ? null : new double[rows];
        final double[] highOut = high == null ? null : new double[rows];
        int i = 0;
        for (final Tuple row : frame) {
            final double prediction = main.predict(row);
            point[i] = prediction;
            if (low != null) {
                lowOut[i] = Math.min(low.predict(row), prediction);
            }
            if (high != null) {
                highOut[i] = Math.max(high.predict(row), prediction);
            }
            i++;
        }
        return new Prediction(point, lowOut, highOut);
    }

    /**
     * Trainiert einen einzelnen Booster mit dem angegebenen Verlust.
     *
     * @param formula Modellformel (Ziel ~ Merkmale)
     * @param data    Trainings-Datensatz
     * @param loss    Verlustfunktion
     * @param props   Hyperparameter
     * @return der trainierte Booster
     */
    private static GradientTreeBoost fit(final Formula formula, final DataFrame data, final Loss loss,
                                         final ForecastProperties props) {
        final GradientTreeBoost.Options options = new GradientTreeBoost.Options(
                loss, props.getGbdtTrees(), props.getGbdtMaxDepth(), props.getGbdtMaxNodes(),
                props.getGbdtNodeSize(), props.getGbdtShrinkage(), props.getGbdtSubsample(), null, null);
        return GradientTreeBoost.fit(formula, data, options);
    }

    /**
     * Baut einen Smile-Datensatz für die Inferenz aus einer zeilenweisen Merkmalsmatrix.
     *
     * <p>Die Zielspalte wird mit Platzhaltern gefüllt, weil die Modellformel sie im Schema erwartet.
     * Bei der Inferenz fallen je Tankstelle nur wenige Dutzend Zeilen an, sodass die spaltenweise
     * Umsortierung hier nicht ins Gewicht fällt.</p>
     *
     * @param features zeilenweise Merkmalsmatrix
     * @return der Datensatz
     */
    private DataFrame toDataFrame(final double[][] features) {
        final int rows = features.length;
        final int featureCount = columnNames.length - 1;
        final TrainingMatrix matrix = new TrainingMatrix(featureCount, rows);
        for (final double[] row : features) {
            matrix.add(row, 0.0);
        }
        return matrix.toDataFrame(columnNames);
    }

    /**
     * Vorhersage-Ergebnis einer Merkmalsmatrix; alle Werte sind Preisänderungen gegenüber dem
     * Ausgangspreis.
     *
     * @param point Punktvorhersage je Datenpunkt
     * @param low   unteres Band (q10) je Datenpunkt oder {@code null}, wenn keine Quantile berechnet wurden
     * @param high  oberes Band (q90) je Datenpunkt oder {@code null}, wenn keine Quantile berechnet wurden
     */
    public record Prediction(double[] point, double[] low, double[] high) {

    }

}
