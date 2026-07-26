package de.lembergmax.tankermax.forecast.ml;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import smile.data.DataFrame;

/**
 * Tests für {@link TrainingMatrix}: die spaltenweise Sammlung, das Kürzen auf die tatsächlich
 * gefüllte Länge und das Verhalten bei erschöpfter Kapazität.
 */
class TrainingMatrixTest {

    /** Spaltennamen einer Matrix mit zwei Merkmalen und Zielspalte. */
    private static final String[] NAMES = {"a", "b", "target"};

    /**
     * Die aufgenommenen Werte landen spaltenrichtig im Datensatz.
     */
    @Test
    void uebernimmtWerteSpaltenrichtig() {
        final TrainingMatrix matrix = new TrainingMatrix(2, 3);
        matrix.add(new double[]{1.0, 2.0}, 10.0);
        matrix.add(new double[]{3.0, 4.0}, 20.0);
        matrix.add(new double[]{5.0, 6.0}, 30.0);

        assertThat(matrix.rows()).isEqualTo(3);
        final DataFrame frame = matrix.toDataFrame(NAMES);

        assertThat(frame.size()).isEqualTo(3);
        assertThat(frame.column("a").getDouble(1)).isEqualTo(3.0);
        assertThat(frame.column("b").getDouble(2)).isEqualTo(6.0);
        assertThat(frame.column("target").getDouble(0)).isEqualTo(10.0);
    }

    /**
     * Wird die Kapazität nicht ausgeschöpft, enthält der Datensatz nur die gefüllten Zeilen.
     */
    @Test
    void kuerztAufGefuellteZeilen() {
        final TrainingMatrix matrix = new TrainingMatrix(2, 1_000);
        matrix.add(new double[]{1.0, 2.0}, 10.0);
        matrix.add(new double[]{3.0, 4.0}, 20.0);

        assertThat(matrix.targets()).containsExactly(10.0, 20.0);
        assertThat(matrix.toDataFrame(NAMES).size()).isEqualTo(2);
    }

    /**
     * Bei erschöpfter Kapazität wird das Beispiel abgelehnt statt einen Fehler auszulösen; der
     * Aufrufer erkennt daran, dass er die Sammlung beenden kann.
     */
    @Test
    void lehntWeitereZeilenBeiVollerKapazitaetAb() {
        final TrainingMatrix matrix = new TrainingMatrix(2, 1);

        assertThat(matrix.add(new double[]{1.0, 2.0}, 10.0)).isTrue();
        assertThat(matrix.add(new double[]{3.0, 4.0}, 20.0)).isFalse();
        assertThat(matrix.rows()).isEqualTo(1);
    }

    /**
     * Eine frisch angelegte Matrix meldet sich als leer.
     */
    @Test
    void meldetLeereMatrix() {
        assertThat(new TrainingMatrix(2, 5).isEmpty()).isTrue();
    }

}
