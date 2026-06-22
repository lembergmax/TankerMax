package de.lembergmax.tankermax.forecast.ml;

import static org.assertj.core.api.Assertions.assertThat;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Rauch-Test für {@link GbdtForecastModel}: Aus einem einfachen, lernbaren Zusammenhang muss das
 * Gradient-Boosting-Modell sinnvolle Punktvorhersagen erzeugen und ein monotones Unsicherheitsband
 * ({@code q10 ≤ Punkt ≤ q90}) liefern. Sichert zugleich ab, dass die Smile-Schnittstelle (Aufbau des
 * Datensatzes, Training mit LAD- und Quantil-Verlust, zeilenweise Vorhersage) zur Laufzeit trägt.
 */
class GbdtForecastModelTest {

    /**
     * Das Modell lernt einen linearen Zusammenhang und liefert ein monotones Band.
     */
    @Test
    void lerntZusammenhangUndLiefertMonotonesBand() {
        final int features = ForecastFeatures.count();
        final int rows = 200;
        final Random random = new Random(42);
        final double[][] data = new double[rows][features];
        final double[] target = new double[rows];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < features; col++) {
                data[row][col] = random.nextDouble();
            }
            final double base = 1.4 + 0.4 * data[row][1];
            data[row][1] = base;
            target[row] = base;
        }

        final ForecastProperties props = new ForecastProperties();
        props.setGbdtTrees(200);
        props.setGbdtMaxDepth(5);
        props.setGbdtShrinkage(0.1);
        props.setGbdtSubsample(1.0);
        props.setQuantilesEnabled(true);

        final GbdtForecastModel model = GbdtForecastModel.train(data, target, props);
        final GbdtForecastModel.Prediction prediction = model.predict(data);

        assertThat(prediction.point()).hasSize(rows);
        double error = 0.0;
        for (int row = 0; row < rows; row++) {
            assertThat(prediction.point()[row]).isFinite();
            assertThat(prediction.low()[row]).isLessThanOrEqualTo(prediction.point()[row]);
            assertThat(prediction.high()[row]).isGreaterThanOrEqualTo(prediction.point()[row]);
            error += Math.abs(prediction.point()[row] - target[row]);
        }
        assertThat(error / rows).isLessThan(0.1);
    }

}
