package de.lembergmax.tankermax.forecast.ml;

import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Baut den Merkmalsvektor (Features) eines Vorhersage-Datenpunkts.
 *
 * <p>Training und Inferenz verwenden ausschließlich diese Klasse, damit die Reihenfolge und
 * Bedeutung der Merkmale stets identisch ist. Die Merkmale umfassen den Horizont, den aktuellen
 * Preis und Verzögerungswerte, Fensterstatistiken, die Zeit seit der letzten Preisänderung,
 * Kalendermerkmale des Ausgangs- und des Zielzeitpunkts (zyklisch über Sinus/Kosinus kodiert) samt
 * Feiertag, die Abweichung vom Regionalpreis sowie das Preisniveau der Tankstelle und der Marke.</p>
 *
 * <p>Alle Merkmale beziehen sich ausschließlich auf die Vergangenheit bis zum Ausgangszeitpunkt sowie
 * auf den Kalender des Zielzeitpunkts; kein Merkmal liest den künftigen Rasterwert. So lässt sich der
 * Vektor bei der Inferenz auch für Zeitpunkte jenseits des bekannten Rasters bilden.</p>
 */
@UtilityClass
public class ForecastFeatures {

    /** Zeitzone, in der die Kalendermerkmale ausgewertet werden (Tankzyklen folgen der Ortszeit). */
    private final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Minuten je Tag, zur Normierung der Tageszeit auf einen Kreis. */
    private final double MINUTES_PER_DAY = 1440.0;

    /** Verzögerungswerte in Stunden, die als Features verwendet werden. */
    private final int[] LAG_HOURS = {1, 2, 3, 6, 12, 24};

    /** Namen der Merkmale in fester Reihenfolge (entspricht der Belegung in {@link #build}). */
    private final String[] FEATURE_NAMES = {
            "horizon_minutes", "current",
            "lag1h", "lag2h", "lag3h", "lag6h", "lag12h", "lag24h",
            "roll_mean_24h", "roll_min_24h", "roll_max_24h", "roll_mean_72h",
            "minutes_since_change", "price_minus_daymin", "price_minus_daymax",
            "origin_hour_sin", "origin_hour_cos", "origin_dow", "origin_weekend",
            "target_hour_sin", "target_hour_cos", "target_dow", "target_weekend", "target_holiday",
            "regional_mean", "station_minus_regional", "station_level", "brand_level"
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
        final HourlyGrid grid = ctx.grid(stationId);
        final StationMeta meta = ctx.meta(stationId);
        final int sph = ctx.stepsPerHour();
        final double current = grid.priceAt(originIndex);
        final long originEpoch = grid.timeAt(originIndex);
        final long targetEpoch = grid.timeAt(originIndex + horizonSteps);
        final double horizonMinutes = (double) horizonSteps * ctx.stepSeconds() / 60.0;

        final double[] window24 = windowStats(grid, originIndex, 24 * sph);
        final double regionalMean = ctx.regionalMean(meta.region(), originEpoch);
        final LocalDateTime originTime = Instant.ofEpochSecond(originEpoch).atZone(BERLIN).toLocalDateTime();
        final LocalDateTime targetTime = Instant.ofEpochSecond(targetEpoch).atZone(BERLIN).toLocalDateTime();

        final double[] f = new double[FEATURE_NAMES.length];
        int i = 0;
        f[i++] = horizonMinutes;
        f[i++] = current;
        for (final int lag : LAG_HOURS) {
            f[i++] = lagPrice(grid, originIndex, lag * sph);
        }
        f[i++] = window24[0];
        f[i++] = window24[1];
        f[i++] = window24[2];
        f[i++] = windowStats(grid, originIndex, 72 * sph)[0];
        f[i++] = minutesSinceChange(grid, originIndex);
        f[i++] = current - window24[1];
        f[i++] = current - window24[2];
        f[i++] = Math.sin(2 * Math.PI * minuteFraction(originTime));
        f[i++] = Math.cos(2 * Math.PI * minuteFraction(originTime));
        f[i++] = originTime.getDayOfWeek().getValue() - 1;
        f[i++] = isWeekend(originTime) ? 1.0 : 0.0;
        f[i++] = Math.sin(2 * Math.PI * minuteFraction(targetTime));
        f[i++] = Math.cos(2 * Math.PI * minuteFraction(targetTime));
        f[i++] = targetTime.getDayOfWeek().getValue() - 1;
        f[i++] = isWeekend(targetTime) ? 1.0 : 0.0;
        f[i++] = GermanHolidays.isHoliday(targetTime.toLocalDate(), meta.state()) ? 1.0 : 0.0;
        f[i++] = regionalMean;
        f[i++] = current - regionalMean;
        f[i++] = ctx.stationLevel(stationId);
        f[i] = ctx.brandLevel(meta.brand());
        return f;
    }

    /**
     * Liefert den Preis mit dem angegebenen Verzögerungsabstand, am Rasteranfang abgeschnitten.
     *
     * @param grid        Zeitraster
     * @param originIndex Ausgangsindex
     * @param lagSteps    Verzögerung in Rasterschritten
     * @return verzögerter Preis
     */
    private double lagPrice(final HourlyGrid grid, final int originIndex, final int lagSteps) {
        final int idx = Math.max(0, originIndex - lagSteps);
        return grid.priceAt(idx);
    }

    /**
     * Berechnet Mittel, Minimum und Maximum über das zurückliegende Fenster bis zum Ausgangsindex.
     *
     * @param grid        Zeitraster
     * @param originIndex Ausgangsindex (einschließlich)
     * @param windowSteps Fensterlänge in Rasterschritten
     * @return Array {Mittel, Minimum, Maximum}
     */
    private double[] windowStats(final HourlyGrid grid, final int originIndex, final int windowSteps) {
        final int start = Math.max(0, originIndex - windowSteps + 1);
        double sum = 0.0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        int count = 0;
        for (int idx = start; idx <= originIndex; idx++) {
            final double price = grid.priceAt(idx);
            sum += price;
            if (price < min) {
                min = price;
            }
            if (price > max) {
                max = price;
            }
            count++;
        }
        return new double[]{sum / count, min, max};
    }

    /**
     * Berechnet die Minuten seit der letzten Preisänderung bis zum Ausgangsindex.
     *
     * @param grid        Zeitraster
     * @param originIndex Ausgangsindex
     * @return Minuten seit der letzten Preisänderung
     */
    private double minutesSinceChange(final HourlyGrid grid, final int originIndex) {
        final double current = grid.priceAt(originIndex);
        int steps = 0;
        for (int idx = originIndex - 1; idx >= 0; idx--) {
            if (Math.abs(grid.priceAt(idx) - current) > 1e-6) {
                break;
            }
            steps++;
        }
        return (double) steps * grid.stepSeconds() / 60.0;
    }

    /**
     * Liefert den Anteil der Tageszeit am Tag (0 bis 1) für die zyklische Kodierung.
     *
     * @param time lokale Zeit
     * @return Anteil der Tageszeit am Tag
     */
    private double minuteFraction(final LocalDateTime time) {
        return (time.getHour() * 60.0 + time.getMinute()) / MINUTES_PER_DAY;
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
