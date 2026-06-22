package de.lembergmax.tankermax.forecast.domain;

import de.lembergmax.tankermax.polling.domain.FuelType;
import de.lembergmax.tankermax.polling.domain.Station;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Gemessene systematische Abweichung einer Tankstelle je Horizont-Abschnitt – die Selbstkorrektur
 * der Vorhersage aus ihren früheren Vorhersagen.
 *
 * <p>Aus dem Abgleich vergangener Vorhersagen mit den tatsächlich eingetretenen Preisen wird je
 * Tankstelle, Kraftstoffart und Horizont-Abschnitt der typische (mediane) Fehler bestimmt und – auf
 * {@code tankermax.forecast.feedback-max-correction-ct} gedeckelt – beim nächsten Lauf
 * gegengerechnet. So verbessert sich die Vorhersage fortlaufend aus ihren eigenen Treffern.</p>
 *
 * <p>Je Tankstelle, Kraftstoffart und Horizont-Abschnitt gibt es genau einen Eintrag; die
 * Eindeutigkeitsbedingung auf {@code (station_id, fuel_type_id, horizon_bucket)} sichert dies ab und
 * dient zugleich dem schnellen Nachschlagen bei der Inferenz.</p>
 */
@Entity
@Table(
        name = "station_forecast_bias",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_station_forecast_bias",
                columnNames = {"station_id", "fuel_type_id", "horizon_bucket"})
)
@Getter
@Setter
public class StationForecastBias {

    /** Technischer Primärschlüssel des Korrektureintrags. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tankstelle, auf die sich die Korrektur bezieht. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    /** Kraftstoffart, auf die sich die Korrektur bezieht. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fuel_type_id", nullable = false)
    private FuelType fuelType;

    /** Horizont-Abschnitt (0 = bis 2 h, 1 = bis 6 h, 2 = bis 12 h, 3 = darüber). */
    @Column(name = "horizon_bucket", nullable = false)
    private int horizonBucket;

    /** Gegenzurechnende systematische Abweichung in Cent/Liter (Vorhersage minus Ist, gedeckelt). */
    @Column(name = "bias_ct", nullable = false)
    private double biasCt;

    /** Anzahl der Stichproben, aus denen die Abweichung gemessen wurde. */
    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    /** Zeitpunkt der letzten Aktualisierung der Korrektur. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
