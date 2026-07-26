package de.lembergmax.tankermax.forecast.ml;

import smile.data.DataFrame;
import smile.data.vector.DoubleVector;
import smile.data.vector.ValueVector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spaltenweise Sammelstruktur der Trainingsbeispiele.
 *
 * <p>Bewusst spalten- statt zeilenweise aufgebaut: Smile hält seine {@link DataFrame} spaltenweise,
 * und {@link DoubleVector} übernimmt ein {@code double[]} ohne es zu kopieren. Werden die Beispiele
 * direkt in Spalten geschrieben, entsteht der Datensatz damit <em>ohne</em> zusätzliche Kopie. Der
 * frühere Weg – zeilenweise Merkmalsmatrix, daraus eine zweite Matrix mit Zielspalte, daraus die
 * spaltenweise {@link DataFrame} – hielt zum Zeitpunkt der Umwandlung drei vollständige Kopien
 * gleichzeitig im Speicher und begrenzte damit auf dem Raspberry Pi die mögliche Zeilenzahl.</p>
 *
 * <p>Die Kapazität wird vorab festgelegt; beim Abschluss werden die Spalten auf die tatsächlich
 * gefüllte Länge gekürzt. Da das spaltenweise geschieht, liegt der zusätzliche Spitzenbedarf bei
 * einer einzelnen Spalte statt bei der gesamten Matrix.</p>
 */
public final class TrainingMatrix {

    /** Spalten des Datensatzes; die letzte Spalte trägt den Zielwert. */
    private double[][] columns;

    /** Anzahl der Merkmalsspalten (ohne Zielspalte). */
    private final int featureCount;

    /** Höchstzahl aufnehmbarer Zeilen. */
    private final int capacity;

    /** Anzahl der bislang gefüllten Zeilen. */
    private int rows;

    /**
     * Legt die Sammelstruktur mit fester Kapazität an.
     *
     * @param featureCount Anzahl der Merkmalsspalten
     * @param capacity     Höchstzahl aufnehmbarer Zeilen
     */
    public TrainingMatrix(final int featureCount, final int capacity) {
        this.featureCount = featureCount;
        this.capacity = capacity;
        this.columns = new double[featureCount + 1][capacity];
    }

    /**
     * Nimmt ein Trainingsbeispiel auf.
     *
     * @param features Merkmalswerte (mindestens {@code featureCount} Einträge)
     * @param target   Zielwert
     * @return {@code true}, wenn das Beispiel aufgenommen wurde; {@code false} bei erschöpfter Kapazität
     */
    public boolean add(final double[] features, final double target) {
        if (rows == capacity) {
            return false;
        }
        for (int column = 0; column < featureCount; column++) {
            columns[column][rows] = features[column];
        }
        columns[featureCount][rows] = target;
        rows++;
        return true;
    }

    /**
     * Liefert die Anzahl der gefüllten Zeilen.
     *
     * @return Anzahl der Zeilen
     */
    public int rows() {
        return rows;
    }

    /**
     * Prüft, ob noch kein Beispiel aufgenommen wurde.
     *
     * @return {@code true}, wenn die Struktur leer ist
     */
    public boolean isEmpty() {
        return rows == 0;
    }

    /**
     * Liefert die Zielwerte der gefüllten Zeilen als eigenständige Kopie.
     *
     * @return Zielwerte
     */
    public double[] targets() {
        return Arrays.copyOf(columns[featureCount], rows);
    }

    /**
     * Wandelt die gesammelten Spalten in einen Smile-Datensatz um und gibt die Sammelstruktur frei.
     *
     * <p>Die Spalten werden dabei an den Datensatz übergeben, nicht kopiert. Die Struktur ist danach
     * nicht mehr verwendbar; das Freigeben der eigenen Referenz erlaubt es der Speicherbereinigung,
     * gekürzte Ursprungsspalten während des Trainings einzusammeln.</p>
     *
     * @param columnNames Spaltennamen (Merkmale gefolgt von der Zielspalte)
     * @return der Datensatz mit genau {@link #rows()} Zeilen
     */
    public DataFrame toDataFrame(final String[] columnNames) {
        final List<ValueVector> vectors = new ArrayList<>(columnNames.length);
        for (int column = 0; column < columnNames.length; column++) {
            final double[] values = columns[column].length == rows
                    ? columns[column]
                    : Arrays.copyOf(columns[column], rows);
            columns[column] = null;
            vectors.add(new DoubleVector(columnNames[column], values));
        }
        columns = null;
        return new DataFrame(vectors.toArray(new ValueVector[0]));
    }

}
