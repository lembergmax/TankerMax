package de.lembergmax.tankermax.forecast.ml;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests für {@link ForecastFeatures}: die feste Anzahl und Reihenfolge der Merkmale, die korrekte
 * Ableitung des Horizont-Merkmals, die Kodierung der Tageszeit sowie – als wichtigste Zusicherung –
 * die Leckfreiheit der Niveau-Merkmale.
 */
class ForecastFeaturesTest {

    /** Rasterabstand von einer Stunde. */
    private static final long HOUR = 3600;

    /** Zeitpunkt des ersten Rasterpunkts: 1. Juli 2026, 00:00 UTC (fern jeder Zeitumstellung). */
    private static final long BASE = 1_782_950_400L;

    /** Anzahl der Rasterpunkte der Testreihe. */
    private static final int POINTS = 96;

    /**
     * Die Anzahl der Merkmalsnamen stimmt mit der gemeldeten Merkmalszahl überein.
     */
    @Test
    void namenUndAnzahlStimmenUeberein() {
        assertThat(ForecastFeatures.featureNames()).hasSize(ForecastFeatures.count());
    }

    /**
     * Der Merkmalsvektor hat die erwartete Länge, enthält nur endliche Werte und das erste Merkmal ist
     * der Horizont in Minuten.
     */
    @Test
    void vektorlaengeUndHorizontMerkmal() {
        final ForecastContext ctx = buildContext(risingPrices(0.0));
        final double[] features = ForecastFeatures.build(ctx, "s1", 30, 5);

        assertThat(features).hasSize(ForecastFeatures.count());
        assertThat(features[0]).isEqualTo(5 * 60.0);
        for (final double value : features) {
            assertThat(value).isFinite();
        }
    }

    /**
     * Die Tageszeit wird roh als Minute des Tages geführt, nicht zyklisch über Sinus und Kosinus.
     *
     * <p>Entscheidungsbäume trennen an Schwellwerten; auf einer Sinuskurve erfasst ein Schwellwert
     * zwei getrennte Tagesabschnitte. Der Tagesrhythmus ist beim Kraftstoffpreis das stärkste Signal
     * und muss daher unmittelbar schneidbar sein.</p>
     */
    @Test
    void tageszeitWirdRohKodiert() {
        final List<String> names = List.of(ForecastFeatures.featureNames());

        assertThat(names).contains("origin_minute_of_day", "target_minute_of_day");
        assertThat(names).noneMatch(name -> name.endsWith("_sin") || name.endsWith("_cos"));
    }

    /**
     * Ein Zielzeitpunkt genau 24 Stunden nach dem Ausgangszeitpunkt trägt dieselbe Tageszeit, und die
     * Minute des Tages liegt im gültigen Bereich.
     */
    @Test
    void zielZeitpunktNachEinemTagHatGleicheTageszeit() {
        final ForecastContext ctx = buildContext(risingPrices(0.0));
        final List<String> names = List.of(ForecastFeatures.featureNames());
        final double[] features = ForecastFeatures.build(ctx, "s1", 30, 24);

        final double originMinute = features[names.indexOf("origin_minute_of_day")];
        final double targetMinute = features[names.indexOf("target_minute_of_day")];

        assertThat(originMinute).isBetween(0.0, 1439.0);
        assertThat(targetMinute).isEqualTo(originMinute);
    }

    /**
     * Preise nach dem Ausgangszeitpunkt verändern den Merkmalsvektor nicht.
     *
     * <p>Das ist die zentrale Zusicherung gegen ein Datenleck: Alle Bezugsgrößen – auch das Preisniveau
     * der Tankstelle, der Region und der Marke – dürfen ausschließlich aus der Vergangenheit bis zum
     * Ausgangszeitpunkt stammen. Würden sie über das gesamte Fenster gemittelt, kennte ein frühes
     * Trainingsbeispiel bereits das spätere Preisniveau, und der gemessene Fehler wäre zu optimistisch.</p>
     */
    @Test
    void spaetereePreiseVeraendernDenVektorNicht() {
        final int origin = 40;
        final ForecastContext ruhig = buildContext(risingPrices(0.0));
        final ForecastContext sprung = buildContext(risingPrices(0.25));

        final double[] ohneSprung = ForecastFeatures.build(ruhig, "s1", origin, 6);
        final double[] mitSprung = ForecastFeatures.build(sprung, "s1", origin, 6);

        assertThat(mitSprung).containsExactly(ohneSprung);
    }

    /**
     * Erzeugt eine Preisreihe, die nach dem Rasterpunkt 40 um den angegebenen Betrag springt.
     *
     * @param spaetererSprung Aufschlag auf alle Preise nach dem Rasterpunkt 40
     * @return Preise je Rasterpunkt
     */
    private double[] risingPrices(final double spaetererSprung) {
        final double[] prices = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            prices[i] = 1.500 + 0.01 * (i % 5) + (i > 40 ? spaetererSprung : 0.0);
        }
        return prices;
    }

    /**
     * Baut einen Kontext mit zwei Tankstellen derselben Region und Marke, damit auch die Regional- und
     * Markenmittel besetzt sind.
     *
     * @param prices Preise der betrachteten Tankstelle je Rasterpunkt
     * @return Kontext für die Feature-Bildung
     */
    private ForecastContext buildContext(final double[] prices) {
        final long[] epochs = new long[POINTS];
        final double[] nachbar = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            epochs[i] = BASE + (long) i * HOUR;
            nachbar[i] = prices[i] + 0.02;
        }
        return ForecastContext.build(List.of(
                        new StationObservations(new StationMeta("s1", "01", "Sachsen", "Aral"),
                                epochs, prices),
                        new StationObservations(new StationMeta("s2", "01", "Sachsen", "Aral"),
                                epochs, nachbar)),
                BASE + (long) (POINTS - 1) * HOUR, HOUR);
    }

}
