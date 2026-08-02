package de.lembergmax.tankermax.forecast.domain;

import de.lembergmax.tankermax.polling.domain.Station;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ein einzelner vorhergesagter Preispunkt der Stundenkurve einer Tankstelle.
 *
 * <p>Je Vorhersagelauf und Tankstelle entsteht für jeden Zeitpunkt des Horizonts ein Eintrag mit dem
 * geschätzten Literpreis und – sofern aktiviert – dem unteren und oberen Quantil des
 * Unsicherheitsbandes. Diese feinkörnige Kurve dient der Darstellung im Dashboard und dem
 * kurzfristigen Abgleich mit den tatsächlich eingetretenen Preisen; sie wird nach
 * {@code tankermax.forecast.retention-curve-days} Tagen aufgeräumt.</p>
 *
 * <p>Je Lauf, Tankstelle und Zielzeitpunkt darf es nur einen Eintrag geben; die
 * Eindeutigkeitsbedingung auf {@code (run_id, station_id, target_at)} sichert dies ab. Der
 * zusätzliche Index auf {@code (station_id, target_at)} stützt die nach Tankstelle und Zeit
 * filternde Auslieferung an das Dashboard.</p>
 */
@Entity
@Table(
        name = "fuel_price_forecast",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_fuel_price_forecast",
                columnNames = {"run_id", "station_id", "target_at"}),
        indexes = @Index(
                name = "idx_fuel_price_forecast_station_time",
                columnList = "station_id, target_at")
)
@Getter
@Setter
public class FuelPriceForecast {

    /** Technischer Primärschlüssel des Vorhersagepunkts. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Vorhersagelauf, zu dem dieser Punkt gehört. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private ForecastRun run;

    /** Tankstelle, für die dieser Punkt vorhergesagt wurde. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    /** Zeitpunkt, für den der Preis vorhergesagt wird. */
    @Column(name = "target_at", nullable = false)
    private Instant targetAt;

    /** Abstand dieses Punkts zum Ausgangszeitpunkt in Minuten. */
    @Column(name = "horizon_minutes", nullable = false)
    private int horizonMinutes;

    /** Vorhergesagter Literpreis in Euro mit drei Nachkommastellen. */
    @Column(name = "predicted_amount", nullable = false, precision = 6, scale = 3)
    private BigDecimal predictedAmount;

    /** Unteres Quantil (q10) des Unsicherheitsbandes; {@code null}, wenn keine Quantile berechnet wurden. */
    @Column(name = "predicted_low", precision = 6, scale = 3)
    private BigDecimal predictedLow;

    /** Oberes Quantil (q90) des Unsicherheitsbandes; {@code null}, wenn keine Quantile berechnet wurden. */
    @Column(name = "predicted_high", precision = 6, scale = 3)
    private BigDecimal predictedHigh;

}
