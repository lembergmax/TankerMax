package de.lembergmax.tankermax.forecast.ml;

import static org.assertj.core.api.Assertions.assertThat;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Rauch-Test für {@link GbdtForecastModel}: Aus einem einfachen, lernbaren Zusammenhang muss das
 * Gradient-Boosting-Modell sinnvolle Punktvorhersagen erzeugen und ein monotones Unsicherheitsband
 * ({@code q10 ≤ Punkt ≤ q90}) liefern. Sichert zugleich ab, dass die Smile-Schnittstelle (spaltenweiser
 * Aufbau des Datensatzes, Training mit LAD- und Quantil-Verlust, zeilenweise Vorhersage) zur Laufzeit
 * trägt und dass ein gespeichertes Modell nach dem Laden dieselben Werte liefert.
 */
class GbdtForecastModelTest {

    /** Anzahl der Trainingszeilen der Testdaten. */
    private static final int ROWS = 200;

    /**
     * Das Modell lernt einen linearen Zusammenhang und liefert ein monotones Band.
     */
    @Test
    void lerntZusammenhangUndLiefertMonotonesBand() {
        final double[][] data = buildFeatures();
        final double[] target = buildTarget(data);
        final GbdtForecastModel model = GbdtForecastModel.train(toMatrix(data, target), props(), true);
        final GbdtForecastModel.Prediction prediction = model.predict(data);

        assertThat(prediction.point()).hasSize(ROWS);
        double error = 0.0;
        for (int row = 0; row < ROWS; row++) {
            assertThat(prediction.point()[row]).isFinite();
            assertThat(prediction.low()[row]).isLessThanOrEqualTo(prediction.point()[row]);
            assertThat(prediction.high()[row]).isGreaterThanOrEqualTo(prediction.point()[row]);
            error += Math.abs(prediction.point()[row] - target[row]);
        }
        assertThat(error / ROWS).isLessThan(0.1);
    }

    /**
     * Ohne Quantile bleiben die Bandgrenzen leer; das spart beim Messlauf zwei komplette Booster.
     */
    @Test
    void ohneQuantileBleibtDasBandLeer() {
        final double[][] data = buildFeatures();
        final GbdtForecastModel model = GbdtForecastModel.train(toMatrix(data, buildTarget(data)),
                props(), false);
        final GbdtForecastModel.Prediction prediction = model.predict(data);

        assertThat(prediction.point()).hasSize(ROWS);
        assertThat(prediction.low()).isNull();
        assertThat(prediction.high()).isNull();
    }

    /**
     * Ein serialisiertes Modell liefert nach dem Laden identische Vorhersagen und erkennt sich als
     * passend zum aktuellen Merkmalsvektor.
     *
     * @throws IOException            wenn das Schreiben oder Lesen fehlschlägt
     * @throws ClassNotFoundException wenn die Klasse beim Laden fehlt
     */
    @Test
    void ueberstehtSerialisierung() throws IOException, ClassNotFoundException {
        final double[][] data = buildFeatures();
        final GbdtForecastModel model = GbdtForecastModel.train(toMatrix(data, buildTarget(data)),
                props(), true);
        final double[] before = model.predict(data).point();

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(model);
        }
        final GbdtForecastModel restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (GbdtForecastModel) in.readObject();
        }

        assertThat(restored.matchesCurrentFeatures()).isTrue();
        assertThat(restored.predict(data).point()).containsExactly(before);
    }

    /**
     * Baut eine Merkmalsmatrix mit reproduzierbaren Zufallswerten.
     *
     * @return Merkmalsmatrix
     */
    private double[][] buildFeatures() {
        final Random random = new Random(42);
        final double[][] data = new double[ROWS][ForecastFeatures.count()];
        for (final double[] row : data) {
            for (int col = 0; col < row.length; col++) {
                row[col] = random.nextDouble();
            }
        }
        return data;
    }

    /**
     * Leitet aus der Merkmalsmatrix einen lernbaren Zielwert ab.
     *
     * @param data Merkmalsmatrix
     * @return Zielwerte
     */
    private double[] buildTarget(final double[][] data) {
        final double[] target = new double[data.length];
        for (int row = 0; row < data.length; row++) {
            target[row] = 1.4 + 0.4 * data[row][1];
        }
        return target;
    }

    /**
     * Überführt Merkmalsmatrix und Zielwerte in die spaltenweise Sammelstruktur.
     *
     * @param data   Merkmalsmatrix
     * @param target Zielwerte
     * @return gefüllte Sammelstruktur
     */
    private TrainingMatrix toMatrix(final double[][] data, final double[] target) {
        final TrainingMatrix matrix = new TrainingMatrix(ForecastFeatures.count(), data.length);
        for (int row = 0; row < data.length; row++) {
            matrix.add(data[row], target[row]);
        }
        return matrix;
    }

    /**
     * Liefert bewusst kleine Hyperparameter, damit der Test schnell bleibt.
     *
     * @return Konfiguration für den Test
     */
    private ForecastProperties props() {
        final ForecastProperties props = new ForecastProperties();
        props.setGbdtTrees(200);
        props.setGbdtMaxDepth(5);
        props.setGbdtMaxNodes(32);
        props.setGbdtNodeSize(5);
        props.setGbdtShrinkage(0.1);
        props.setGbdtSubsample(1.0);
        props.setQuantilesEnabled(true);
        return props;
    }

}
