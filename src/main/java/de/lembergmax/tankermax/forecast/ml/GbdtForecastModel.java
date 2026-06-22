package de.lembergmax.tankermax.forecast.ml;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import smile.base.cart.Loss;
import smile.data.DataFrame;
import smile.data.Tuple;
import smile.data.formula.Formula;
import smile.regression.GradientTreeBoost;

/**
 * Gradient-Boosting-Modell (Smile) für die Preisvorhersage einer Kraftstoffart.
 *
 * <p>Es kapselt bis zu drei Booster: das Hauptmodell minimiert den mittleren absoluten Fehler
 * ({@link Loss#lad()}, entspricht dem L1-Ziel des Python-Vorbilds), die beiden optionalen
 * Quantil-Modelle ({@link Loss#quantile(double)}) schätzen das untere und obere Band der
 * Unsicherheit. Sämtliche Bäume sind reines Java und benötigen keine nativen Bibliotheken, was den
 * Einsatz auf dem Raspberry Pi ermöglicht.</p>
 */
public final class GbdtForecastModel {

    /** Name der Zielspalte im Trainings-Datensatz. */
    private static final String TARGET = "target";

    /** Hauptmodell (Punktvorhersage). */
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
     * Trainiert das Modell aus einer Merkmalsmatrix und der zugehörigen Zielgröße.
     *
     * @param features Merkmalsmatrix (Zeilen = Datenpunkte, Spalten = Merkmale)
     * @param target   Zielwerte je Datenpunkt
     * @param props    Hyperparameter und Quantil-Einstellungen
     * @return das trainierte Modell
     */
    public static GbdtForecastModel train(final double[][] features, final double[] target,
                                          final ForecastProperties props) {
        final String[] featureNames = ForecastFeatures.featureNames();
        final String[] columnNames = new String[featureNames.length + 1];
        System.arraycopy(featureNames, 0, columnNames, 0, featureNames.length);
        columnNames[featureNames.length] = TARGET;

        final DataFrame data = toDataFrame(features, target, columnNames);
        final Formula formula = Formula.lhs(TARGET);

        final GradientTreeBoost main = fit(formula, data, Loss.lad(), props);
        GradientTreeBoost low = null;
        GradientTreeBoost high = null;
        if (props.isQuantilesEnabled()) {
            low = fit(formula, data, Loss.quantile(props.getQuantileLow()), props);
            high = fit(formula, data, Loss.quantile(props.getQuantileHigh()), props);
        }
        return new GbdtForecastModel(main, low, high, columnNames);
    }

    /**
     * Sagt die Punktvorhersage sowie – falls vorhanden – das untere und obere Band voraus.
     *
     * <p>Die Band-Grenzen werden monoton erzwungen, sodass stets {@code low ≤ Punkt ≤ high} gilt.</p>
     *
     * @param features Merkmalsmatrix der vorherzusagenden Datenpunkte
     * @return Vorhersage mit Punktwerten und – sofern trainiert – Bandgrenzen
     */
    public Prediction predict(final double[][] features) {
        final int rows = features.length;
        final DataFrame frame = toDataFrame(features, new double[rows], columnNames);
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
     * Baut einen Smile-Datensatz aus Merkmalsmatrix und Zielwerten.
     *
     * @param features    Merkmalsmatrix
     * @param target      Zielwerte (bei der Inferenz Platzhalter)
     * @param columnNames Spaltennamen (Merkmale gefolgt von der Zielspalte)
     * @return der Datensatz
     */
    private static DataFrame toDataFrame(final double[][] features, final double[] target,
                                         final String[] columnNames) {
        final int rows = features.length;
        final int featureCount = columnNames.length - 1;
        final double[][] matrix = new double[rows][columnNames.length];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(features[row], 0, matrix[row], 0, featureCount);
            matrix[row][featureCount] = target[row];
        }
        return DataFrame.of(matrix, columnNames);
    }

    /**
     * Vorhersage-Ergebnis einer Merkmalsmatrix.
     *
     * @param point Punktvorhersage je Datenpunkt
     * @param low   unteres Band (q10) je Datenpunkt oder {@code null}, wenn keine Quantile berechnet wurden
     * @param high  oberes Band (q90) je Datenpunkt oder {@code null}, wenn keine Quantile berechnet wurden
     */
    public record Prediction(double[] point, double[] low, double[] high) {

    }

}
