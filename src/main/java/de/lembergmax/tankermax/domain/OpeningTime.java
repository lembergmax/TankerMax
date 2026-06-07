package de.lembergmax.tankermax.domain;

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
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;

/**
 * Reguläre Öffnungszeit einer Tankstelle für einen bestimmten Tagesbereich.
 */
@Entity
@Table(name = "opening_time", indexes = @Index(name = "idx_opening_time_station", columnList = "station_id"))
@Getter
@Setter
public class OpeningTime {

    /** Technischer Primärschlüssel der Öffnungszeit. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tankstelle, zu der diese Öffnungszeit gehört. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    /** Tagesbereich der Öffnungszeit, wie von der API geliefert (zum Beispiel {@code Mo-Fr}). */
    @Column(nullable = false)
    private String description;

    /** Uhrzeit, zu der die Tankstelle öffnet. */
    @Column(name = "start_time")
    private LocalTime startTime;

    /** Uhrzeit, zu der die Tankstelle schließt. */
    @Column(name = "end_time")
    private LocalTime endTime;

}
