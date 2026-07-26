package de.lembergmax.tankermax.forecast.ml;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vorberechneter Kontext für den Aufbau der Vorhersage-Features.
 *
 * <p>Bündelt je Tankstelle das Zeitraster und die Stammdaten und stellt daraus abgeleitete
 * Bezugsgrößen bereit: das Preismittel der Region und das der Marke, jeweils je Rasterzeitpunkt,
 * sowie ein globales Mittel als Rückfall.</p>
 *
 * <p>Beide Bezugsgrößen sind bewusst <em>zeitpunktbezogen</em> und damit leckfrei: Sie mitteln nur
 * über die Preise, die zum jeweiligen Ausgangszeitpunkt tatsächlich galten. Ein früher liegendes
 * Trainingsbeispiel kann so kein Wissen über spätere Preise erhalten. Das Preisniveau der einzelnen
 * Tankstelle wird aus demselben Grund nicht hier, sondern als nachlaufendes Mittel direkt aus ihrem
 * Zeitraster gebildet (siehe {@link ForecastFeatures}).</p>
 *
 * <p>Die Zeitreihen liegen als {@code double[]} über einem gemeinsamen Rasterursprung statt als
 * {@code Map<Long, Double>}: Bei mehreren Millionen Trainingsbeispielen spart das je Beispiel zwei
 * Hash-Zugriffe samt Boxing.</p>
 */
public final class ForecastContext {

    /**
     * Zeitraster je Tankstellen-Kennung.
     *
     * <p>Bewusst in der Reihenfolge der geladenen Beobachtungen gehalten: Training, Stichprobenauswahl
     * und das Schreiben der Kurve durchlaufen die Tankstellen in genau dieser Reihenfolge, sodass zwei
     * Läufe über demselben Datenbestand auch dasselbe Ergebnis liefern.</p>
     */
    private final Map<String, HourlyGrid> grids;

    /** Stammdaten je Tankstellen-Kennung. */
    private final Map<String, StationMeta> meta;

    /** Preismittel je Region und gemeinsamem Rasterindex. */
    private final Map<String, double[]> regionalMean;

    /** Preismittel je Marke und gemeinsamem Rasterindex. */
    private final Map<String, double[]> brandMean;

    /** Globales Preismittel als Rückfall. */
    private final double globalLevel;

    /** Zeitpunkt des gemeinsamen Rasterursprungs (Sekunden seit der Epoche). */
    private final long baseEpochSecond;

    /** Rasterabstand in Sekunden. */
    private final long stepSeconds;

    /** Anzahl der Rasterpunkte je Stunde. */
    private final int stepsPerHour;

    /** Anzahl der Rasterpunkte des gemeinsamen Rasters. */
    private final int slotCount;

    /**
     * Erzeugt den Kontext aus den bereits berechneten Bausteinen.
     *
     * @param grids           Zeitraster je Tankstelle
     * @param meta            Stammdaten je Tankstelle
     * @param regionalMean    Preismittel je Region und gemeinsamem Rasterindex
     * @param brandMean       Preismittel je Marke und gemeinsamem Rasterindex
     * @param globalLevel     globales Preismittel
     * @param baseEpochSecond Zeitpunkt des gemeinsamen Rasterursprungs
     * @param stepSeconds     Rasterabstand in Sekunden
     * @param stepsPerHour    Anzahl der Rasterpunkte je Stunde
     * @param slotCount       Anzahl der Rasterpunkte des gemeinsamen Rasters
     */
    private ForecastContext(final Map<String, HourlyGrid> grids, final Map<String, StationMeta> meta,
                            final Map<String, double[]> regionalMean, final Map<String, double[]> brandMean,
                            final double globalLevel, final long baseEpochSecond, final long stepSeconds,
                            final int stepsPerHour, final int slotCount) {
        this.grids = grids;
        this.meta = meta;
        this.regionalMean = regionalMean;
        this.brandMean = brandMean;
        this.globalLevel = globalLevel;
        this.baseEpochSecond = baseEpochSecond;
        this.stepSeconds = stepSeconds;
        this.stepsPerHour = stepsPerHour;
        this.slotCount = slotCount;
    }

    /**
     * Baut den Kontext aus den Roh-Beobachtungen aller Tankstellen.
     *
     * @param stations       Roh-Beobachtungen je Tankstelle
     * @param endEpochSecond letzter zu füllender Rasterzeitpunkt (Sekunden seit der Epoche)
     * @param stepSeconds    Rasterabstand in Sekunden
     * @return der gefüllte Kontext
     */
    public static ForecastContext build(final List<StationObservations> stations,
                                        final long endEpochSecond, final long stepSeconds) {
        final Map<String, HourlyGrid> grids = new LinkedHashMap<>();
        final Map<String, StationMeta> meta = new HashMap<>();
        for (final StationObservations station : stations) {
            final HourlyGrid grid = PriceGridResampler.resample(station, endEpochSecond, stepSeconds);
            if (grid != null) {
                grids.put(station.meta().id(), grid);
                meta.put(station.meta().id(), station.meta());
            }
        }
        if (grids.isEmpty()) {
            return new ForecastContext(grids, meta, Map.of(), Map.of(), 0.0, 0L, stepSeconds,
                    stepsPerHour(stepSeconds), 0);
        }

        long base = Long.MAX_VALUE;
        for (final HourlyGrid grid : grids.values()) {
            base = Math.min(base, grid.timeAt(0));
        }
        final long end = Math.floorDiv(endEpochSecond, stepSeconds) * stepSeconds;
        final int slotCount = (int) ((end - base) / stepSeconds) + 1;

        final Map<String, double[]> regionAcc = new HashMap<>();
        final Map<String, double[]> regionCount = new HashMap<>();
        final Map<String, double[]> brandAcc = new HashMap<>();
        final Map<String, double[]> brandCount = new HashMap<>();
        double globalSum = 0.0;
        int globalCount = 0;

        for (final Map.Entry<String, HourlyGrid> entry : grids.entrySet()) {
            final HourlyGrid grid = entry.getValue();
            final StationMeta stationMeta = meta.get(entry.getKey());
            final String region = stationMeta.region();
            final String brand = brandKey(stationMeta.brand());
            final double[] regionSum = regionAcc.computeIfAbsent(region, key -> new double[slotCount]);
            final double[] regionHits = regionCount.computeIfAbsent(region, key -> new double[slotCount]);
            final double[] brandSum = brandAcc.computeIfAbsent(brand, key -> new double[slotCount]);
            final double[] brandHits = brandCount.computeIfAbsent(brand, key -> new double[slotCount]);
            final int offset = (int) ((grid.timeAt(0) - base) / stepSeconds);
            for (int slot = 0; slot < grid.size(); slot++) {
                final int shared = offset + slot;
                if (shared < 0 || shared >= slotCount) {
                    continue;
                }
                final double price = grid.priceAt(slot);
                regionSum[shared] += price;
                regionHits[shared] += 1.0;
                brandSum[shared] += price;
                brandHits[shared] += 1.0;
                globalSum += price;
                globalCount++;
            }
        }

        final double globalLevel = globalCount == 0 ? 0.0 : globalSum / globalCount;
        return new ForecastContext(grids, meta, toMeans(regionAcc, regionCount, globalLevel),
                toMeans(brandAcc, brandCount, globalLevel), globalLevel, base, stepSeconds,
                stepsPerHour(stepSeconds), slotCount);
    }

    /**
     * Liefert das Zeitraster einer Tankstelle.
     *
     * @param stationId Kennung der Tankstelle
     * @return Zeitraster oder {@code null}, wenn keines vorliegt
     */
    public HourlyGrid grid(final String stationId) {
        return grids.get(stationId);
    }

    /**
     * Liefert die Stammdaten einer Tankstelle.
     *
     * @param stationId Kennung der Tankstelle
     * @return Stammdaten oder {@code null}, wenn keine vorliegen
     */
    public StationMeta meta(final String stationId) {
        return meta.get(stationId);
    }

    /**
     * Liefert das Preismittel der Region zum angegebenen Rasterzeitpunkt.
     *
     * @param region      Regionsschlüssel
     * @param epochSecond Rasterzeitpunkt
     * @return regionales Mittel oder das globale Mittel als Rückfall
     */
    public double regionalMean(final String region, final long epochSecond) {
        return valueAt(regionalMean.get(region), epochSecond);
    }

    /**
     * Liefert das Preismittel der Marke zum angegebenen Rasterzeitpunkt.
     *
     * @param brand       Markenname
     * @param epochSecond Rasterzeitpunkt
     * @return Markenmittel oder das globale Mittel als Rückfall
     */
    public double brandLevel(final String brand, final long epochSecond) {
        return valueAt(brandMean.get(brandKey(brand)), epochSecond);
    }

    /**
     * Liefert die Kennungen aller Tankstellen mit Zeitraster.
     *
     * @return Tankstellen-Kennungen
     */
    public Set<String> stationIds() {
        return grids.keySet();
    }

    /**
     * Liefert den Rasterabstand in Sekunden.
     *
     * @return Rasterabstand in Sekunden
     */
    public long stepSeconds() {
        return stepSeconds;
    }

    /**
     * Liefert die Anzahl der Rasterpunkte je Stunde.
     *
     * @return Rasterpunkte je Stunde
     */
    public int stepsPerHour() {
        return stepsPerHour;
    }

    /**
     * Liest einen Wert der gemeinsamen Zeitreihe, mit dem globalen Mittel als Rückfall.
     *
     * @param series      Zeitreihe über dem gemeinsamen Raster oder {@code null}
     * @param epochSecond Rasterzeitpunkt
     * @return Wert der Zeitreihe oder das globale Mittel
     */
    private double valueAt(final double[] series, final long epochSecond) {
        if (series == null) {
            return globalLevel;
        }
        final long offset = Math.floorDiv(epochSecond - baseEpochSecond, stepSeconds);
        if (offset < 0 || offset >= slotCount) {
            return globalLevel;
        }
        return series[(int) offset];
    }

    /**
     * Wandelt Summen und Zähler je Schlüssel in Mittelwerte um; leere Rasterpunkte erhalten das
     * globale Mittel.
     *
     * @param sums        Summen je Schlüssel und Rasterindex
     * @param counts      Zähler je Schlüssel und Rasterindex
     * @param globalLevel globales Mittel als Rückfall
     * @return Mittelwerte je Schlüssel und Rasterindex
     */
    private static Map<String, double[]> toMeans(final Map<String, double[]> sums,
                                                 final Map<String, double[]> counts,
                                                 final double globalLevel) {
        final Map<String, double[]> means = new HashMap<>();
        sums.forEach((key, sum) -> {
            final double[] hits = counts.get(key);
            final double[] mean = new double[sum.length];
            for (int slot = 0; slot < sum.length; slot++) {
                mean[slot] = hits[slot] == 0.0 ? globalLevel : sum[slot] / hits[slot];
            }
            means.put(key, mean);
        });
        return means;
    }

    /**
     * Bestimmt die Anzahl der Rasterpunkte je Stunde.
     *
     * @param stepSeconds Rasterabstand in Sekunden
     * @return Rasterpunkte je Stunde (mindestens 1)
     */
    private static int stepsPerHour(final long stepSeconds) {
        return Math.max(1, (int) Math.round(3600.0 / stepSeconds));
    }

    /**
     * Normalisiert einen Markennamen zu einem Schlüssel (leerer Name wird zu einem Sammelschlüssel).
     *
     * @param brand Markenname oder {@code null}
     * @return Markenschlüssel
     */
    private static String brandKey(final String brand) {
        return brand == null || brand.isBlank() ? "?" : brand;
    }

}
