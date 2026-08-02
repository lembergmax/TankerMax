package de.lembergmax.tankermax.forecast.domain;

import de.lembergmax.tankermax.polling.domain.FuelType;
import de.lembergmax.tankermax.polling.domain.Station;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.time.LocalDate;

/**
 * Kompakte Tageszusammenfassung einer Vorhersage je Tankstelle, Kraftstoffart und Vorhersagetag.
 *
 * <p>Diese Zeile fasst die feinkörnige Kurve eines Tages zu wenigen Kennzahlen zusammen
 * (prognostiziertes Tagestief und -mittel samt Quantilen) und trägt zusätzlich den Tanktipp. Sie
 * wird bis zu einem Jahr aufbewahrt ({@code tankermax.forecast.retention-summary-days}) und ist damit
 * der dauerhafte Rückblick auf die Vorhersagegüte.</p>
 *
 * <p>Sobald der Vorhersagetag verstrichen ist, ergänzt der Abgleich die tatsächlich eingetretene
 * Preislage, die Treffer-Klasse und die Abweichung. Alle Felder hängen vom vollständigen Schlüssel
 * {@code (station_id, fuel_type_id, forecast_date)} ab, der zugleich als Eindeutigkeitsbedingung
 * Doubletten verhindert; der Index auf {@code forecast_date} stützt den Abgleichslauf und das
 * Aufräumen.</p>
 */
@Entity
@Table(
        name = "forecast_daily_summary",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_forecast_daily_summary",
                columnNames = {"station_id", "fuel_type_id", "forecast_date"}),
        indexes = @Index(
                name = "idx_forecast_daily_summary_date",
                columnList = "forecast_date")
)
@Getter
@Setter
public class ForecastDailySummary {

    /** Technischer Primärschlüssel der Tageszusammenfassung. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tankstelle, auf die sich die Zusammenfassung bezieht. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    /** Kraftstoffart, auf die sich die Zusammenfassung bezieht. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fuel_type_id", nullable = false)
    private FuelType fuelType;

    /** Vorhergesagter Kalendertag (lokale Zeitzone). */
    @Column(name = "forecast_date", nullable = false)
    private LocalDate forecastDate;

    /** Zeitpunkt, zu dem diese Vorhersage erzeugt wurde. */
    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /** Version des erzeugenden Modells (Zeitstempel des Trainings als Zeichenkette). */
    @Column(name = "model_version", length = 32)
    private String modelVersion;

    /** Preis zum Ausgangszeitpunkt der Vorhersage (Euro/Liter); {@code null}, wenn unbekannt. */
    @Column(name = "current_amount", precision = 6, scale = 3)
    private BigDecimal currentAmount;

    /** Prognostiziertes Tagestief (Euro/Liter). */
    @Column(name = "predicted_low_amount", precision = 6, scale = 3)
    private BigDecimal predictedLowAmount;

    /** Zeitpunkt des prognostizierten Tagestiefs; {@code null}, wenn nicht bestimmt. */
    @Column(name = "predicted_low_at")
    private Instant predictedLowAt;

    /** Prognostiziertes Tagesmittel (Euro/Liter). */
    @Column(name = "predicted_mean_amount", precision = 6, scale = 3)
    private BigDecimal predictedMeanAmount;

    /** Unteres Quantil (q10) des prognostizierten Tagestiefs; {@code null}, wenn keine Quantile berechnet wurden. */
    @Column(name = "predicted_low_q10", precision = 6, scale = 3)
    private BigDecimal predictedLowQ10;

    /** Oberes Quantil (q90) des prognostizierten Tagestiefs; {@code null}, wenn keine Quantile berechnet wurden. */
    @Column(name = "predicted_low_q90", precision = 6, scale = 3)
    private BigDecimal predictedLowQ90;

    /** Tanktipp für diesen Tag; nur auf dem heutigen Tag gesetzt, sonst {@code null}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation", length = 16)
    private RefuelRecommendation recommendation;

    /** Kurzbegründung des Tanktipps; {@code null}, wenn keine Empfehlung vorliegt. */
    @Column(name = "recommendation_reason", length = 64)
    private String recommendationReason;

    /** Erwartete Ersparnis (Cent/Liter) beim Warten auf das prognostizierte Tief; {@code null}, wenn keine Empfehlung vorliegt. */
    @Column(name = "expected_saving_ct")
    private Double expectedSavingCt;

    /** Tatsächlich eingetretenes Tagestief (Euro/Liter); {@code null}, solange der Tag nicht abgeglichen wurde. */
    @Column(name = "actual_low_amount", precision = 6, scale = 3)
    private BigDecimal actualLowAmount;

    /** Tatsächlich eingetretenes Tagesmittel (Euro/Liter); {@code null}, solange der Tag nicht abgeglichen wurde. */
    @Column(name = "actual_mean_amount", precision = 6, scale = 3)
    private BigDecimal actualMeanAmount;

    /** Treffer-Klasse des Abgleichs; {@code null}, solange der Tag nicht abgeglichen wurde. */
    @Enumerated(EnumType.STRING)
    @Column(name = "accuracy_class", length = 16)
    private AccuracyClass accuracyClass;

    /** Absolute Abweichung des prognostizierten Tagestiefs vom tatsächlichen (Cent/Liter); {@code null} bis zum Abgleich. */
    @Column(name = "low_abs_error_ct")
    private Double lowAbsErrorCt;

    /** Zeitpunkt des Abgleichs; {@code null}, solange noch nicht abgeglichen wurde. */
    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

}
