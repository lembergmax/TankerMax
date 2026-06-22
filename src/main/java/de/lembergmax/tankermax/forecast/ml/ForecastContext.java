package de.lembergmax.tankermax.forecast.ml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vorberechneter Kontext für den Aufbau der Vorhersage-Features.
 *
 * <p>Bündelt je Tankstelle das Zeitraster und die Stammdaten und stellt daraus abgeleitete
 * Bezugsgrößen bereit: das regionale Preismittel je Rasterzeitpunkt (für die Abweichung einer
 * Tankstelle von ihrer Region), das Preisniveau je Tankstelle und je Marke sowie ein globales
 * Mittel als Rückfall. Diese Größen werden einmalig berechnet und von Training und Inferenz
 * gemeinsam genutzt, damit beide identische Features erzeugen.</p>
 */
public final class ForecastContext {

    /** Zeitraster je Tankstellen-Kennung. */
    private final Map<String, HourlyGrid> grids;

    /** Stammdaten je Tankstellen-Kennung. */
    private final Map<String, StationMeta> meta;

    /** Regionales Preismittel je Region und Rasterzeitpunkt. */
    private final Map<String, Map<Long, Double>> regionalMean;

    /** Preisniveau (Mittel über das Fenster) je Tankstelle. */
    private final Map<String, Double> stationLevel;

    /** Preisniveau (Mittel über das Fenster) je Marke. */
    private final Map<String, Double> brandLevel;

    /** Globales Preismittel als Rückfall. */
    private final double globalLevel;

    /** Rasterabstand in Sekunden. */
    private final long stepSeconds;

    /** Anzahl der Rasterpunkte je Stunde. */
    private final int stepsPerHour;

    /**
     * Erzeugt den Kontext aus den bereits berechneten Bausteinen.
     *
     * @param grids        Zeitraster je Tankstelle
     * @param meta         Stammdaten je Tankstelle
     * @param regionalMean regionales Preismittel je Region und Rasterzeitpunkt
     * @param stationLevel Preisniveau je Tankstelle
     * @param brandLevel   Preisniveau je Marke
     * @param globalLevel  globales Preismittel
     * @param stepSeconds  Rasterabstand in Sekunden
     * @param stepsPerHour Anzahl der Rasterpunkte je Stunde
     */
    private ForecastContext(final Map<String, HourlyGrid> grids, final Map<String, StationMeta> meta,
                            final Map<String, Map<Long, Double>> regionalMean,
                            final Map<String, Double> stationLevel, final Map<String, Double> brandLevel,
                            final double globalLevel, final long stepSeconds, final int stepsPerHour) {
        this.grids = grids;
        this.meta = meta;
        this.regionalMean = regionalMean;
        this.stationLevel = stationLevel;
        this.brandLevel = brandLevel;
        this.globalLevel = globalLevel;
        this.stepSeconds = stepSeconds;
        this.stepsPerHour = stepsPerHour;
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
        final Map<String, HourlyGrid> grids = new HashMap<>();
        final Map<String, StationMeta> meta = new HashMap<>();
        final Map<String, Map<Long, double[]>> regionAcc = new HashMap<>();
        final Map<String, Double> stationLevel = new HashMap<>();
        final Map<String, List<Double>> brandLevels = new HashMap<>();
        double globalSum = 0.0;
        int globalCount = 0;

        for (final StationObservations station : stations) {
            final HourlyGrid grid = PriceGridResampler.resample(station, endEpochSecond, stepSeconds);
            if (grid == null) {
                continue;
            }
            final StationMeta stationMeta = station.meta();
            grids.put(stationMeta.id(), grid);
            meta.put(stationMeta.id(), stationMeta);

            double sum = 0.0;
            final Map<Long, double[]> regionMap = regionAcc.computeIfAbsent(stationMeta.region(), key -> new HashMap<>());
            for (int slot = 0; slot < grid.size(); slot++) {
                final double price = grid.priceAt(slot);
                sum += price;
                final double[] agg = regionMap.computeIfAbsent(grid.timeAt(slot), key -> new double[2]);
                agg[0] += price;
                agg[1] += 1.0;
            }
            final double level = sum / grid.size();
            stationLevel.put(stationMeta.id(), level);
            brandLevels.computeIfAbsent(brandKey(stationMeta.brand()), key -> new ArrayList<>()).add(level);
            globalSum += level;
            globalCount++;
        }

        final Map<String, Map<Long, Double>> regionalMean = new HashMap<>();
        regionAcc.forEach((region, slots) -> {
            final Map<Long, Double> means = new HashMap<>();
            slots.forEach((time, agg) -> means.put(time, agg[0] / agg[1]));
            regionalMean.put(region, means);
        });

        final Map<String, Double> brandLevel = new HashMap<>();
        brandLevels.forEach((brand, levels) -> brandLevel.put(brand,
                levels.stream().mapToDouble(Double::doubleValue).average().orElse(0.0)));

        final double globalLevel = globalCount == 0 ? 0.0 : globalSum / globalCount;
        final int stepsPerHour = Math.max(1, (int) Math.round(3600.0 / stepSeconds));
        return new ForecastContext(grids, meta, regionalMean, stationLevel, brandLevel,
                globalLevel, stepSeconds, stepsPerHour);
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
     * Liefert das regionale Preismittel zu einem Rasterzeitpunkt.
     *
     * @param region      Regionsschlüssel
     * @param epochSecond Rasterzeitpunkt
     * @return regionales Mittel oder das globale Mittel als Rückfall
     */
    public double regionalMean(final String region, final long epochSecond) {
        final Map<Long, Double> means = regionalMean.get(region);
        if (means == null) {
            return globalLevel;
        }
        return means.getOrDefault(epochSecond, globalLevel);
    }

    /**
     * Liefert das Preisniveau einer Tankstelle.
     *
     * @param stationId Kennung der Tankstelle
     * @return Preisniveau oder das globale Mittel als Rückfall
     */
    public double stationLevel(final String stationId) {
        return stationLevel.getOrDefault(stationId, globalLevel);
    }

    /**
     * Liefert das Preisniveau einer Marke.
     *
     * @param brand Markenname
     * @return Preisniveau oder das globale Mittel als Rückfall
     */
    public double brandLevel(final String brand) {
        return brandLevel.getOrDefault(brandKey(brand), globalLevel);
    }

    /**
     * Liefert die Kennungen aller Tankstellen mit Zeitraster.
     *
     * @return Tankstellen-Kennungen
     */
    public java.util.Set<String> stationIds() {
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
     * Normalisiert einen Markennamen zu einem Schlüssel (leerer Name wird zu einem Sammelschlüssel).
     *
     * @param brand Markenname oder {@code null}
     * @return Markenschlüssel
     */
    private static String brandKey(final String brand) {
        return brand == null || brand.isBlank() ? "?" : brand;
    }

}
