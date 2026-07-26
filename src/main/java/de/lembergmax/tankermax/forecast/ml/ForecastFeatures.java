package de.lembergmax.tankermax.forecast.ml;

import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Baut den Merkmalsvektor (Features) eines Vorhersage-Datenpunkts.
 *
 * <p>Training und Inferenz verwenden ausschließlich diese Klasse, damit die Reihenfolge und
 * Bedeutung der Merkmale stets identisch ist.</p>
 *
 * <p>Zwei Entwurfsentscheidungen sind für die Genauigkeit wesentlich:</p>
 *
 * <ul>
 *   <li><b>Differenzen statt absoluter Preise.</b> Bis auf den aktuellen Preis selbst werden alle
 *       Preisbezüge – Verzögerungswerte, Fenstermittel, Tagesextrema, Regional-, Marken- und
 *       Stationsniveau – als Abstand zum aktuellen Preis geführt. Da das Modell die
 *       <em>Preisänderung</em> vorhersagt, sind diese Größen damit weitgehend niveauunabhängig und
 *       bleiben auch dann gültig, wenn das Preisniveau aus dem Bereich des Trainingsfensters
 *       herausläuft.</li>
 *   <li><b>Rohe Tageszeit statt zyklischer Kodierung.</b> Eine Sinus-/Kosinus-Kodierung ist auf
 *       lineare Modelle zugeschnitten. Entscheidungsbäume trennen an Schwellwerten; dort erfasst ein
 *       Schwellwert auf einer Sinuskurve zwei getrennte Tagesabschnitte, während die rohe Minute des
 *       Tages jede Tageszeit mit zwei Schnitten isoliert. Der Tagesrhythmus ist das stärkste Signal
 *       im Kraftstoffpreis und wird deshalb in der für Bäume unmittelbar nutzbaren Form geliefert.</li>
 * </ul>
 *
 * <p>Alle Merkmale beziehen sich ausschließlich auf die Vergangenheit bis zum Ausgangszeitpunkt sowie
 * auf den Kalender des Zielzeitpunkts; kein Merkmal liest den künftigen Rasterwert. So lässt sich der
 * Vektor bei der Inferenz auch für Zeitpunkte jenseits des bekannten Rasters bilden.</p>
 */
@UtilityClass
public class ForecastFeatures {

    /** Zeitzone, in der die Kalendermerkmale ausgewertet werden (Tankzyklen folgen der Ortszeit). */
    private final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Verzögerungswerte in Stunden, die als Abstand zum aktuellen Preis einfließen. */
    private final int[] LAG_HOURS = {1, 2, 3, 6, 12, 24, 168};

    /** Fensterlängen in Stunden, deren Mittel als Abstand zum aktuellen Preis einfließen. */
    private final int[] MEAN_WINDOW_HOURS = {24, 72, 168};

    /** Fensterlänge in Stunden, über die das nachlaufende Preisniveau der Tankstelle gemittelt wird. */
    private final int LEVEL_WINDOW_HOURS = 720;

    /** Namen der Merkmale in fester Reihenfolge (entspricht der Belegung in {@link #build}). */
    private final String[] FEATURE_NAMES = {
            "horizon_minutes", "current",
            "diff_lag1h", "diff_lag2h", "diff_lag3h", "diff_lag6h", "diff_lag12h", "diff_lag24h",
            "diff_lag168h",
            "diff_mean24h", "diff_mean72h", "diff_mean168h",
            "diff_daymin", "diff_daymax", "day_range", "minutes_since_change",
            "origin_minute_of_day", "origin_dow", "origin_weekend", "origin_holiday",
            "target_minute_of_day", "target_dow", "target_weekend", "target_holiday",
            "diff_regional", "diff_station_level", "diff_brand"
    };

    /**
     * Liefert die Namen der Merkmale in fester Reihenfolge.
     *
     * @return Merkmalsnamen
     */
    public String[] featureNames() {
        return FEATURE_NAMES.clone();
    }

    /**
     * Liefert die Anzahl der Merkmale.
     *
     * @return Anzahl der Merkmale
     */
    public int count() {
        return FEATURE_NAMES.length;
    }

    /**
     * Baut den Merkmalsvektor für einen Ausgangszeitpunkt und Horizont.
     *
     * @param ctx          vorberechneter Kontext
     * @param stationId    Kennung der Tankstelle
     * @param originIndex  Rasterindex des Ausgangszeitpunkts
     * @param horizonSteps Horizont in Rasterschritten
     * @return Merkmalsvektor mit {@link #count()} Einträgen
     */
    public double[] build(final ForecastContext ctx, final String stationId,
                          final int originIndex, final int horizonSteps) {
        final double[] out = new double[FEATURE_NAMES.length];
        build(ctx, stationId, originIndex, horizonSteps, out);
        return out;
    }

    /**
     * Baut den Merkmalsvektor in einen bereitgestellten Puffer.
     *
     * <p>Diese Form vermeidet beim Training je Beispiel eine Array-Anlage; der Aufrufer kann denselben
     * Puffer für Millionen Beispiele wiederverwenden.</p>
     *
     * @param ctx          vorberechneter Kontext
     * @param stationId    Kennung der Tankstelle
     * @param originIndex  Rasterindex des Ausgangszeitpunkts
     * @param horizonSteps Horizont in Rasterschritten
     * @param out          Zielpuffer mit mindestens {@link #count()} Einträgen
     */
    public void build(final ForecastContext ctx, final String stationId, final int originIndex,
                      final int horizonSteps, final double[] out) {
        final HourlyGrid grid = ctx.grid(stationId);
        final StationMeta meta = ctx.meta(stationId);
        final int sph = ctx.stepsPerHour();
        final double current = grid.priceAt(originIndex);
        final long originEpoch = grid.timeAt(originIndex);
        final long targetEpoch = grid.timeAt(originIndex + horizonSteps);
        final LocalDateTime originTime = localTime(originEpoch);
        final LocalDateTime targetTime = localTime(targetEpoch);
        final double dayMin = grid.dayMin(originIndex);
        final double dayMax = grid.dayMax(originIndex);

        int i = 0;
        out[i++] = (double) horizonSteps * ctx.stepSeconds() / 60.0;
        out[i++] = current;
        for (final int lag : LAG_HOURS) {
            out[i++] = current - grid.laggedPrice(originIndex, lag * sph);
        }
        for (final int window : MEAN_WINDOW_HOURS) {
            out[i++] = current - grid.meanOver(originIndex, window * sph);
        }
        out[i++] = current - dayMin;
        out[i++] = current - dayMax;
        out[i++] = dayMax - dayMin;
        out[i++] = grid.minutesSinceChange(originIndex);
        out[i++] = minuteOfDay(originTime);
        out[i++] = originTime.getDayOfWeek().getValue() - 1;
        out[i++] = isWeekend(originTime) ? 1.0 : 0.0;
        out[i++] = GermanHolidays.isHoliday(originTime.toLocalDate(), meta.state()) ? 1.0 : 0.0;
        out[i++] = minuteOfDay(targetTime);
        out[i++] = targetTime.getDayOfWeek().getValue() - 1;
        out[i++] = isWeekend(targetTime) ? 1.0 : 0.0;
        out[i++] = GermanHolidays.isHoliday(targetTime.toLocalDate(), meta.state()) ? 1.0 : 0.0;
        out[i++] = current - ctx.regionalMean(meta.region(), originEpoch);
        out[i++] = current - grid.meanOver(originIndex, LEVEL_WINDOW_HOURS * sph);
        out[i] = current - ctx.brandLevel(meta.brand(), originEpoch);
    }

    /**
     * Wandelt einen Zeitpunkt in die deutsche Ortszeit um.
     *
     * @param epochSecond Zeitpunkt in Sekunden seit der Epoche
     * @return lokale Zeit
     */
    private LocalDateTime localTime(final long epochSecond) {
        return Instant.ofEpochSecond(epochSecond).atZone(BERLIN).toLocalDateTime();
    }

    /**
     * Liefert die Minute des Tages (0 bis 1439).
     *
     * @param time lokale Zeit
     * @return Minute des Tages
     */
    private double minuteOfDay(final LocalDateTime time) {
        return time.getHour() * 60.0 + time.getMinute();
    }

    /**
     * Prüft, ob die Zeit auf ein Wochenende fällt.
     *
     * @param time lokale Zeit
     * @return {@code true} bei Samstag oder Sonntag
     */
    private boolean isWeekend(final LocalDateTime time) {
        final int dow = time.getDayOfWeek().getValue();
        return dow >= 6;
    }

}
