package de.lembergmax.tankermax.polling.domain;

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

import java.time.Instant;

/**
 * Einzelne Preisbeobachtung einer Tankstelle zu einem Abfragezeitpunkt.
 *
 * <p>Eine Beobachtung bündelt den Tankstellenstatus eines Abfragezyklus. Die
 * zugehörigen Einzelpreise je Kraftstoffart sind als {@link FuelPrice} ausgelagert.</p>
 *
 * <p>Je Tankstelle und Abfragezeitpunkt darf es nur eine Beobachtung geben; die
 * Eindeutigkeitsbedingung auf {@code (station_id, observed_at)} verhindert Doubletten
 * und dient zugleich als Index für zeitlich sortierte Verlaufsabfragen.</p>
 *
 * <p>Der zusätzliche Index auf {@code (status, observed_at)} beschleunigt die Dashboard-Abfragen,
 * die nach geöffneten Beobachtungen filtern und über den Beobachtungszeitpunkt eingrenzen oder
 * sortieren; ohne ihn müsste die mit jedem Pollzyklus wachsende Tabelle dafür vollständig gelesen
 * werden.</p>
 */
@Entity
@Table(
        name = "price_observation",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_observation_station_time",
                columnNames = {"station_id", "observed_at"}),
        indexes = @Index(
                name = "idx_observation_status_time",
                columnList = "status, observed_at")
)
@Getter
@Setter
public class PriceObservation {

    /** Technischer Primärschlüssel der Preisbeobachtung. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tankstelle, auf die sich diese Beobachtung bezieht. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    /** Zeitpunkt, zu dem die Preise abgefragt wurden. */
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    /** Status der Tankstelle zum Abfragezeitpunkt. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ObservationStatus status;

}
