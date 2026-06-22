package de.lembergmax.tankermax.forecast.domain;

import de.lembergmax.tankermax.polling.domain.FuelType;
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

import java.time.Instant;

/**
 * Ein einzelner Vorhersagelauf für genau eine Kraftstoffart.
 *
 * <p>Je täglichem Training entsteht pro Kraftstoffart ein Lauf, der die zugehörige Stundenkurve
 * ({@link FuelPriceForecast}) klammert und die Metadaten des Modells trägt (Erzeugungszeitpunkt,
 * Ausgangszeitpunkt, Horizont, Modellversion, Trainingsumfang und Validierungsfehler).</p>
 *
 * <p>Je Kraftstoffart und Erzeugungszeitpunkt darf es nur einen Lauf geben; die
 * Eindeutigkeitsbedingung auf {@code (fuel_type_id, generated_at)} verhindert Doubletten und stützt
 * zugleich das Nachschlagen des jüngsten Laufs. Der Index auf {@code generated_at} beschleunigt das
 * Aufräumen alter Läufe.</p>
 */
@Entity
@Table(
        name = "forecast_run",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_forecast_run_fuel_generated",
                columnNames = {"fuel_type_id", "generated_at"}),
        indexes = @Index(
                name = "idx_forecast_run_generated",
                columnList = "generated_at")
)
@Getter
@Setter
public class ForecastRun {

    /** Technischer Primärschlüssel des Vorhersagelaufs. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Kraftstoffart, für die dieser Lauf die Vorhersage erzeugt hat. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fuel_type_id", nullable = false)
    private FuelType fuelType;

    /** Zeitpunkt, zu dem dieser Lauf erzeugt wurde. */
    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /** Ausgangszeitpunkt der Vorhersage (Beginn des Horizonts, auf die volle Stunde gelegt). */
    @Column(name = "origin_at", nullable = false)
    private Instant originAt;

    /** Vorhersagehorizont dieses Laufs in Stunden. */
    @Column(name = "horizon_hours", nullable = false)
    private int horizonHours;

    /** Zeitliche Auflösung der Kurve in Minuten. */
    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    /** Version des erzeugenden Modells (Zeitstempel des Trainings als Zeichenkette). */
    @Column(name = "model_version", length = 32)
    private String modelVersion;

    /** Anzahl der Trainingszeilen, aus denen das Modell dieses Laufs gelernt hat. */
    @Column(name = "train_rows", nullable = false)
    private int trainRows;

    /** Mittlerer absoluter Fehler (Cent/Liter) auf dem zeitlichen Validierungsausschnitt; {@code null}, wenn nicht gemessen. */
    @Column(name = "train_mae")
    private Double trainMae;

}
