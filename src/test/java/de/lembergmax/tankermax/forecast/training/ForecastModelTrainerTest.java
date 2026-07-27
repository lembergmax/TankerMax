package de.lembergmax.tankermax.forecast.training;

import static org.assertj.core.api.Assertions.assertThat;

import de.lembergmax.tankermax.forecast.config.ForecastProperties;
import de.lembergmax.tankermax.forecast.ml.ForecastContext;
import de.lembergmax.tankermax.forecast.ml.StationMeta;
import de.lembergmax.tankermax.forecast.ml.StationObservations;
import de.lembergmax.tankermax.forecast.ml.TrainedModel;
import de.lembergmax.tankermax.forecast.training.ForecastModelTrainer.CurvePoint;
import de.lembergmax.tankermax.forecast.training.ForecastModelTrainer.Result;
import de.lembergmax.tankermax.forecast.training.ForecastModelTrainer.StationForecast;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Test des gesamten Trainings- und Vorhersageweges an einer künstlichen Preisreihe mit bekanntem
 * Tagesrhythmus.
 *
 * <p>Die Reihe bildet den typischen deutschen Kraftstoff-Tageszyklus nach: teuer am frühen Morgen,
 * fallend über den Tag, Tiefpunkt am frühen Abend, dazu ein langsamer Anstieg des Preisniveaus über
 * die Wochen. Ein brauchbares Modell muss diesen Rhythmus wiedergeben.</p>
 *
 * <p>Die Zusicherungen sind bewusst so gewählt, dass sie die zuvor vorhandenen Fehler aufdecken
 * würden: Eine Vorhersage, die nur alle drei Stunden trainiert wurde, liefert für den ersten
 * Stundenschritt keinen brauchbaren Wert; eine auf das absolute Preisniveau trainierte Vorhersage
 * fällt bei steigendem Niveau zum Median des Trainingsfensters zurück; und ein Modell, das nur acht
 * von 24 Tagesstunden gesehen hat, glättet den Tagesverlauf zu einer nahezu flachen Linie.</p>
 */
class ForecastModelTrainerTest {

    /** Zeitzone der Preisreihe. */
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Rasterabstand von einer Stunde. */
    private static final long HOUR = 3600;

    /** Anzahl der Tankstellen der Testreihe. */
    private static final int STATIONS = 4;

    /** Länge der Testreihe in Rasterpunkten (21 Tage). */
    private static final int POINTS = 21 * 24;

    /** Beginn der Testreihe (Mitternacht Ortszeit). */
    private static final long BASE =
            LocalDate.of(2026, 6, 1).atStartOfDay(BERLIN).toEpochSecond();

    /** Zeitpunkt des Laufs: letzter Rasterpunkt der Reihe. */
    private static final Instant NOW = Instant.ofEpochSecond(BASE + (long) (POINTS - 1) * HOUR);

    /** Ergebnis des einmalig ausgeführten Laufs. */
    private static Result result;

    /** Das trainierte Modell des Laufs. */
    private static TrainedModel trained;

    /**
     * Trainiert einmalig und erzeugt die Vorhersage; alle Zusicherungen prüfen dasselbe Ergebnis.
     */
    @BeforeAll
    static void trainiereEinmalig() {
        final ForecastProperties props = props();
        final ForecastModelTrainer trainer = new ForecastModelTrainer(props);
        final ForecastContext ctx = ForecastContext.build(buildStations(), NOW.getEpochSecond(), HOUR);

        trained = trainer.train("E5", ctx, NOW);
        assertThat(trained).as("Training muss ein Modell liefern").isNotNull();
        result = trainer.infer("E5", ctx, trained, Map.of(), NOW);
    }

    /**
     * Es entsteht je Tankstelle eine vollständige Kurve über den gesamten Horizont.
     */
    @Test
    void liefertVollstaendigeKurveJeTankstelle() {
        assertThat(result.stations()).hasSize(STATIONS);
        for (final StationForecast station : result.stations()) {
            assertThat(station.curve()).hasSize(72);
            assertThat(station.curve().get(0).horizonMinutes()).isEqualTo(60);
            assertThat(station.dailies()).isNotEmpty();
        }
    }

    /**
     * Der gemessene Validierungsfehler wird ausgewiesen und liegt bei dieser gut vorhersagbaren Reihe
     * im niedrigen Cent-Bereich.
     */
    @Test
    void weistValidierungsfehlerAus() {
        assertThat(trained.trainMae()).isNotNull();
        assertThat(trained.trainMae()).isLessThan(2.0);
        assertThat(trained.trainRows()).isPositive();
    }

    /**
     * Auch wenn der Validierungsausschnitt ausgedünnt werden muss, gelingt die Fehlermessung.
     *
     * <p>Übersteigt die Zahl der Kandidaten die Obergrenze, greift die zufällige Auswahl: Das Feld
     * wird mit einem Sicherheitszuschlag angelegt, aber nur bis etwa zur Obergrenze gefüllt. Die
     * überzähligen Zeilen bleiben leer und dürfen nicht in die Vorhersage gelangen. Ohne das Kürzen
     * scheiterte der gesamte Trainingslauf hier – und zwar erst nach der vollständigen Modellbildung,
     * also nach Stunden Rechenzeit.</p>
     */
    @Test
    void misstFehlerAuchBeiAusgeduenntemValidierungsausschnitt() {
        final ForecastProperties props = props();
        props.setMaxValidationRows(500);
        final ForecastContext ctx = ForecastContext.build(buildStations(), NOW.getEpochSecond(), HOUR);

        final TrainedModel schmal = new ForecastModelTrainer(props).train("E5", ctx, NOW);

        assertThat(schmal).isNotNull();
        assertThat(schmal.trainMae()).isNotNull().isLessThan(2.0);
    }

    /**
     * Die Kurve gibt den Tagesrhythmus wieder statt ihn zu einer flachen Linie zu glätten.
     *
     * <p>Der künstliche Zyklus hat eine Spanne von zehn Cent. Ein Modell, das nur einen Teil der
     * Tagesstunden gesehen hat, mittelt sie weitgehend weg.</p>
     */
    @Test
    void gibtTagesrhythmusWieder() {
        final List<CurvePoint> tag = curveOf(0).subList(0, 24);
        final double hoch = tag.stream().mapToDouble(CurvePoint::predicted).max().orElseThrow();
        final double tief = tag.stream().mapToDouble(CurvePoint::predicted).min().orElseThrow();

        assertThat((hoch - tief) * 100.0).as("Tagesspanne in Cent").isGreaterThan(4.0);
    }

    /**
     * Das vorhergesagte Tagestief liegt am Abend, wo es die Reihe auch tatsächlich hat.
     */
    @Test
    void findetAbendtief() {
        final CurvePoint tief = curveOf(0).subList(0, 24).stream()
                .min((a, b) -> Double.compare(a.predicted(), b.predicted()))
                .orElseThrow();

        assertThat(tief.targetAt().atZone(BERLIN).getHour()).isBetween(16, 22);
    }

    /**
     * Auch die kurzen Horizonte treffen, nicht nur die langen.
     *
     * <p>Mit dem früheren Schrittweiten-Raster war der erste trainierte Horizont drei Stunden; die
     * ersten beiden Stundenschritte waren reine Nachbarschaftswerte.</p>
     */
    @Test
    void trifftAuchKurzeHorizonte() {
        for (int station = 0; station < STATIONS; station++) {
            final List<CurvePoint> curve = curveOf(station);
            for (int h = 1; h <= 3; h++) {
                assertThat(Math.abs(curve.get(h - 1).predicted() - priceAt(station, POINTS - 1 + h)) * 100.0)
                        .as("Abweichung bei Tankstelle %d, Horizont %d h in Cent", station, h)
                        .isLessThan(3.0);
            }
        }
    }

    /**
     * Die Kurve setzt auf dem aktuellen Preis auf und läuft nicht zum Mittel des Trainingsfensters
     * zurück, obwohl das Preisniveau über die Reihe deutlich gestiegen ist.
     */
    @Test
    void bleibtBeimAktuellenPreisniveau() {
        final double aktuell = priceAt(0, POINTS - 1);
        final double fenstermittel = mittelUeberReihe(0);
        final double ersterPunkt = curveOf(0).get(0).predicted();

        assertThat(aktuell - fenstermittel).as("Die Reihe muss überhaupt gestiegen sein")
                .isGreaterThan(0.05);
        assertThat(Math.abs(ersterPunkt - aktuell))
                .isLessThan(Math.abs(ersterPunkt - fenstermittel));
    }

    /**
     * Liefert die Vorhersagekurve einer bestimmten Tankstelle.
     *
     * @param station Nummer der Tankstelle
     * @return Kurvenpunkte dieser Tankstelle
     */
    private List<CurvePoint> curveOf(final int station) {
        return result.stations().stream()
                .filter(forecast -> ("s" + station).equals(forecast.stationId()))
                .findFirst()
                .orElseThrow()
                .curve();
    }

    /**
     * Baut die künstlichen Beobachtungsreihen aller Tankstellen.
     *
     * @return Beobachtungen je Tankstelle
     */
    private static List<StationObservations> buildStations() {
        final List<StationObservations> stations = new ArrayList<>(STATIONS);
        for (int station = 0; station < STATIONS; station++) {
            final long[] epochs = new long[POINTS];
            final double[] prices = new double[POINTS];
            for (int index = 0; index < POINTS; index++) {
                epochs[index] = BASE + (long) index * HOUR;
                prices[index] = priceAt(station, index);
            }
            stations.add(new StationObservations(
                    new StationMeta("s" + station, "01", "Sachsen", station % 2 == 0 ? "Aral" : "Shell"),
                    epochs, prices));
        }
        return stations;
    }

    /**
     * Liefert den künstlichen Preis einer Tankstelle an einem Rasterpunkt.
     *
     * @param station Nummer der Tankstelle
     * @param index   Rasterindex (darf über die Reihe hinausreichen)
     * @return Literpreis in Euro
     */
    private static double priceAt(final int station, final int index) {
        final int hour = Instant.ofEpochSecond(BASE + (long) index * HOUR).atZone(BERLIN).getHour();
        final double niveau = 1.60 + 0.02 * station + 0.004 * (index / 24.0);
        return Math.round((niveau + tageszyklus(hour)) * 1000.0) / 1000.0;
    }

    /**
     * Liefert den Aufschlag des Tagesrhythmus zur angegebenen Stunde: teuer am Morgen, Tief am Abend.
     *
     * @param hour Stunde des Tages (0–23)
     * @return Aufschlag in Euro
     */
    private static double tageszyklus(final int hour) {
        if (hour >= 5 && hour <= 8) {
            return 0.05;
        }
        if (hour >= 9 && hour <= 12) {
            return 0.02;
        }
        if (hour >= 13 && hour <= 16) {
            return -0.01;
        }
        if (hour >= 17 && hour <= 21) {
            return -0.05;
        }
        return 0.03;
    }

    /**
     * Berechnet das Preismittel einer Tankstelle über die gesamte Reihe.
     *
     * @param station Nummer der Tankstelle
     * @return mittlerer Literpreis
     */
    private static double mittelUeberReihe(final int station) {
        double sum = 0.0;
        for (int index = 0; index < POINTS; index++) {
            sum += priceAt(station, index);
        }
        return sum / POINTS;
    }

    /**
     * Liefert bewusst kleine Hyperparameter, damit der Test in wenigen Sekunden läuft.
     *
     * @return Konfiguration für den Test
     */
    private static ForecastProperties props() {
        final ForecastProperties props = new ForecastProperties();
        props.setMinHistoryDays(14);
        props.setValidationDays(3);
        props.setMaxTrainRows(12_000);
        props.setQuantilesEnabled(false);
        props.setGbdtTrees(80);
        props.setGbdtMaxDepth(6);
        props.setGbdtMaxNodes(32);
        props.setGbdtNodeSize(5);
        props.setGbdtShrinkage(0.1);
        props.setGbdtSubsample(1.0);
        return props;
    }

}
